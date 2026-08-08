package com.studyflow.tutor;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.repo.AiJobRepository;
import com.studyflow.jobs.service.JobDispatcher;
import com.studyflow.library.dto.UploadResponse;
import com.studyflow.support.DatabaseCleanerExtension;
import com.studyflow.tutor.dto.ConversationResponse;
import com.studyflow.tutor.dto.MessageResponse;
import com.studyflow.tutor.dto.SendMessageRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Real Postgres + real Voyage (retrieval) + real Groq (streamed chat) — no mocked step, same
 * discipline as {@code SummaryGenerationIntegrationTest}. The tutor endpoint streams SSE over a
 * plain HTTP response; {@code TestRestTemplate} can't consume it incrementally, but since these
 * tests only assert the final result (not live delivery, which the streaming unit test already
 * covers against a fake server), reading the full response body as text after the emitter
 * completes is a faithful enough check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class TutorChatIntegrationTest {

    private static final String SAMPLE_TEXT = """
            # ACID Properties

            ACID stands for Atomicity, Consistency, Isolation, and Durability. These four
            properties guarantee that database transactions are processed reliably even in the
            presence of errors, power failures, or concurrent access.

            ## Atomicity and Durability

            Atomicity means a transaction is all-or-nothing: either every operation in it
            succeeds, or none of them take effect. Durability means that once a transaction
            commits, its changes survive any subsequent system crash.
            """;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JobDispatcher jobDispatcher;
    @Autowired
    private AiJobRepository aiJobRepository;

    /**
     * Grounded and explain-beyond-notes are merged into one test method sharing a single
     * ingestion, rather than each ingesting (and retrieving) its own document — this account's
     * Voyage tier is capped at 3 requests/minute with no payment method on file (see
     * docs/DECISIONS.md), and four independent real Voyage calls across two methods reliably
     * exceeded that cap. Two calls (one ingest, two retrievals) fits far more comfortably.
     */
    @Test
    void groundedAndBeyondNotesRepliesBehaveDifferently() throws InterruptedException {
        String accessToken = registerAndLogin("tutor-grounded");
        UUID documentId = uploadAndIngest(accessToken);
        UUID conversationId = createConversation(accessToken, documentId);

        SseExchange grounded = sendMessage(accessToken, conversationId,
                new SendMessageRequest("What does atomicity mean for a database transaction?", false));

        assertThat(grounded.status()).isEqualTo(HttpStatus.OK);
        assertThat(grounded.tokenText()).isNotBlank();
        assertThat(grounded.done()).isNotNull();
        assertThat((Boolean) grounded.done().get("grounded")).isTrue();
        assertThat((Boolean) grounded.done().get("beyondNotes")).isFalse();
        @SuppressWarnings("unchecked")
        List<Object> citations = (List<Object>) grounded.done().get("citations");
        assertThat(citations).isNotEmpty();

        List<MessageResponse> afterFirstTurn = getMessages(accessToken, conversationId);
        assertThat(afterFirstTurn).hasSize(2);
        assertThat(afterFirstTurn.get(0).role()).isEqualTo("USER");
        assertThat(afterFirstTurn.get(1).role()).isEqualTo("ASSISTANT");
        assertThat(afterFirstTurn.get(1).content()).isNotBlank();
        assertThat(afterFirstTurn.get(1).grounded()).isTrue();

        SseExchange beyondNotes = sendMessage(accessToken, conversationId,
                new SendMessageRequest("Unrelated to the notes: name a famous mountain range in South America.",
                        true));

        assertThat(beyondNotes.status()).isEqualTo(HttpStatus.OK);
        assertThat(beyondNotes.done()).isNotNull();
        assertThat((Boolean) beyondNotes.done().get("grounded")).isFalse();
        assertThat((Boolean) beyondNotes.done().get("beyondNotes")).isTrue();
        assertThat(beyondNotes.tokenText()).isNotBlank();

        List<MessageResponse> afterSecondTurn = getMessages(accessToken, conversationId);
        assertThat(afterSecondTurn).hasSize(4);
    }

    @Test
    void anotherUsersConversationIsNotFound() throws InterruptedException {
        String ownerToken = registerAndLogin("tutor-owner");
        UUID documentId = uploadAndIngest(ownerToken);
        UUID conversationId = createConversation(ownerToken, documentId);

        String otherToken = registerAndLogin("tutor-intruder");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(otherToken);
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/conversations/" + conversationId,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("CONVERSATION_NOT_FOUND");
    }

    @Test
    void conversationCannotBeCreatedBeforeIngestionFinishes() {
        String accessToken = registerAndLogin("tutor-notready");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "tutor-notready-" + System.nanoTime());
        org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("plain text file".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "quick.txt";
            }
        });
        ResponseEntity<UploadResponse> uploadResponse = restTemplate.postForEntity("/api/v1/documents",
                new HttpEntity<>(body, headers), UploadResponse.class);
        UUID documentId = uploadResponse.getBody().documentId();

        // Deliberately do NOT run the ingest job — document stays UPLOADED.
        HttpHeaders bearerOnly = new HttpHeaders();
        bearerOnly.setBearerAuth(accessToken);
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/documents/" + documentId + "/conversations",
                HttpMethod.POST, new HttpEntity<>(bearerOnly), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("DOCUMENT_NOT_READY");
    }

    private record SseExchange(org.springframework.http.HttpStatusCode status, String tokenText,
            java.util.Map<String, Object> done) {
    }

    @SuppressWarnings("unchecked")
    private SseExchange sendMessage(String accessToken, UUID conversationId, SendMessageRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/conversations/" + conversationId
                + "/messages", HttpMethod.POST, new HttpEntity<>(request, headers), String.class);

        StringBuilder tokenText = new StringBuilder();
        java.util.Map<String, Object> done = null;
        if (response.getBody() != null) {
            for (SseEvent event : parseSse(response.getBody())) {
                if ("token".equals(event.name())) {
                    Object delta = readJson(event.data()).get("delta");
                    if (delta != null) {
                        tokenText.append(delta);
                    }
                } else if ("done".equals(event.name())) {
                    done = readJson(event.data());
                } else if ("error".equals(event.name())) {
                    throw new AssertionError("Tutor stream returned an error event: " + event.data());
                }
            }
        }
        return new SseExchange(response.getStatusCode(), tokenText.toString(), done);
    }

    private record SseEvent(String name, String data) {
    }

    private List<SseEvent> parseSse(String body) {
        List<SseEvent> events = new ArrayList<>();
        for (String rawEvent : body.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : rawEvent.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).strip());
                }
            }
            if (name != null) {
                events.add(new SseEvent(name, data.toString()));
            }
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> readJson(String json) {
        return new tools.jackson.databind.json.JsonMapper().readValue(json, java.util.Map.class);
    }

    private UUID createConversation(String accessToken, UUID documentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<ConversationResponse> response = restTemplate.postForEntity(
                "/api/v1/documents/" + documentId + "/conversations", new HttpEntity<>(headers),
                ConversationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private List<MessageResponse> getMessages(String accessToken, UUID conversationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<MessageResponse[]> response = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages", HttpMethod.GET, new HttpEntity<>(headers),
                MessageResponse[].class);
        return List.of(response.getBody());
    }

    private UUID uploadAndIngest(String accessToken) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "tutor-ingest-" + System.nanoTime());
        org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(SAMPLE_TEXT.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "acid.md";
            }
        });
        ResponseEntity<UploadResponse> uploadResponse = restTemplate.postForEntity("/api/v1/documents",
                new HttpEntity<>(body, headers), UploadResponse.class);
        UUID documentId = uploadResponse.getBody().documentId();
        UUID ingestJobId = uploadResponse.getBody().jobId();

        UUID claimed = jobDispatcher.pollOnce();
        assertThat(claimed).isEqualTo(ingestJobId);
        AiJob ingestJob = awaitJobTerminal(ingestJobId);
        assertThat(ingestJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        return documentId;
    }

    private AiJob awaitJobTerminal(UUID jobId) throws InterruptedException {
        // Background dispatcher polling is disabled in tests; a job requeued after a transient
        // failure (e.g. a real provider rate limit) needs this loop to keep reclaiming it via
        // pollOnce(), same as the real @Scheduled dispatcher would — otherwise a requeued job
        // just sits QUEUED for the rest of the wait window. Generous bound to absorb the job
        // engine's own backoff (5s/20s/80s, specs/07-jobs-and-async.md) on top of normal latency.
        for (int i = 0; i < 300; i++) {
            jobDispatcher.pollOnce();
            AiJob job = aiJobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED) {
                return job;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Ingest job did not reach a terminal state in time");
    }

    private String registerAndLogin(String prefix) {
        String email = prefix + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Tutor User", (short) 2000), Object.class);
        ResponseEntity<AccessTokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        return login.getBody().accessToken();
    }
}
