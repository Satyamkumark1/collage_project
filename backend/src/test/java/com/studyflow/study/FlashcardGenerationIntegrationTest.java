package com.studyflow.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.MeResponse;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.repo.AiJobRepository;
import com.studyflow.jobs.service.JobDispatcher;
import com.studyflow.library.dto.UploadResponse;
import com.studyflow.study.domain.Flashcard;
import com.studyflow.study.dto.FlashcardJobResponse;
import com.studyflow.study.dto.FlashcardResponse;
import com.studyflow.study.dto.FlashcardReviewRequest;
import com.studyflow.study.repo.FlashcardRepository;
import com.studyflow.study.service.Sm2Calculator;
import com.studyflow.support.DatabaseCleanerExtension;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class FlashcardGenerationIntegrationTest {

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
    @Autowired
    private FlashcardRepository flashcardRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void endToEndGenerateListAndReviewUpdatesSm2State() throws InterruptedException {
        String accessToken = registerAndLogin();
        UUID documentId = uploadAndIngest(accessToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.add("Idempotency-Key", "flashcard-test-" + System.nanoTime());
        ResponseEntity<FlashcardJobResponse> response = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/flashcards", HttpMethod.POST, new HttpEntity<>(headers),
                FlashcardJobResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID jobId = response.getBody().jobId();

        UUID claimed = jobDispatcher.pollOnce();
        assertThat(claimed).isEqualTo(jobId);
        AiJob finishedJob = awaitJobTerminal(jobId);
        assertThat(finishedJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth(accessToken);
        ResponseEntity<FlashcardResponse[]> listResponse = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/flashcards", HttpMethod.GET, new HttpEntity<>(getHeaders),
                FlashcardResponse[].class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotEmpty();
        FlashcardResponse card = listResponse.getBody()[0];
        assertThat(card.frontMd()).isNotBlank();
        assertThat(card.backMd()).isNotBlank();
        assertThat(card.citations().isArray()).isTrue();
        assertThat(card.citations()).isNotEmpty();
        assertThat(card.repetitions()).isZero();
        assertThat(card.easeFactor()).isEqualByComparingTo("2.50");

        ResponseEntity<FlashcardResponse[]> dueResponse = restTemplate.exchange("/api/v1/flashcards/due",
                HttpMethod.GET, new HttpEntity<>(getHeaders), FlashcardResponse[].class);
        assertThat(dueResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dueResponse.getBody()).extracting(FlashcardResponse::id).contains(card.id());

        HttpHeaders reviewHeaders = new HttpHeaders();
        reviewHeaders.setBearerAuth(accessToken);
        reviewHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<FlashcardResponse> reviewResponse = restTemplate.exchange(
                "/api/v1/flashcards/" + card.id() + "/review", HttpMethod.POST,
                new HttpEntity<>(new FlashcardReviewRequest(4), reviewHeaders), FlashcardResponse.class);
        assertThat(reviewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        FlashcardResponse reviewed = reviewResponse.getBody();
        assertThat(reviewed.repetitions()).isEqualTo(1);
        assertThat(reviewed.intervalDays()).isEqualTo(1);
        assertThat(reviewed.lastQuality()).isEqualTo((short) 4);
        assertThat(reviewed.lastReviewedAt()).isNotNull();
        assertThat(reviewed.dueAt()).isAfter(card.dueAt());
    }

    @Test
    void requestingFlashcardsBeforeIngestionFinishesIsRejected() {
        String accessToken = registerAndLogin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "not-ready-flashcards-" + System.nanoTime());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("plain text file".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "quick.txt";
            }
        });
        ResponseEntity<UploadResponse> uploadResponse = restTemplate.postForEntity("/api/v1/documents",
                new HttpEntity<>(body, headers), UploadResponse.class);
        UUID documentId = uploadResponse.getBody().documentId();

        HttpHeaders flashcardHeaders = new HttpHeaders();
        flashcardHeaders.setBearerAuth(accessToken);
        flashcardHeaders.add("Idempotency-Key", "flashcards-not-ready-" + System.nanoTime());
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/documents/" + documentId + "/flashcards",
                HttpMethod.POST, new HttpEntity<>(flashcardHeaders), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = objectMapper.readTree(response.getBody());
        assertThat(problem.get("code").asString()).isEqualTo("DOCUMENT_NOT_READY");
    }

    @Test
    void aStaleReviewNeverSilentlyOverwritesANewerOne() throws InterruptedException {
        String accessToken = registerAndLogin();
        UUID documentId = uploadAndIngest(accessToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.add("Idempotency-Key", "flashcard-lock-" + System.nanoTime());
        ResponseEntity<FlashcardJobResponse> response = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/flashcards", HttpMethod.POST, new HttpEntity<>(headers),
                FlashcardJobResponse.class);
        UUID jobId = response.getBody().jobId();
        jobDispatcher.pollOnce();
        AiJob finishedJob = awaitJobTerminal(jobId);
        assertThat(finishedJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        UUID cardId = flashcardRepository.findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(documentId,
                UUID.fromString(currentUserId(accessToken))).get(0).getId();

        // Two independent reads, each representing what a separate concurrent request would have
        // seen — both start at version 0. Each findById/saveAndFlush below runs in its own
        // transaction (no surrounding @Transactional on this test), matching two real HTTP
        // requests each with their own transaction boundary.
        Flashcard firstReaderCopy = flashcardRepository.findById(cardId).orElseThrow();
        Flashcard secondReaderCopy = flashcardRepository.findById(cardId).orElseThrow();

        firstReaderCopy.applyReview(Sm2Calculator.compute(firstReaderCopy.getEaseFactor(),
                firstReaderCopy.getIntervalDays(), firstReaderCopy.getRepetitions(), 4, ZoneId.of("Asia/Kolkata")),
                (short) 4);
        flashcardRepository.saveAndFlush(firstReaderCopy);

        secondReaderCopy.applyReview(Sm2Calculator.compute(secondReaderCopy.getEaseFactor(),
                secondReaderCopy.getIntervalDays(), secondReaderCopy.getRepetitions(), 1, ZoneId.of("Asia/Kolkata")),
                (short) 1);
        assertThatThrownBy(() -> flashcardRepository.saveAndFlush(secondReaderCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // The first (successful) review's state is what's actually persisted — the second,
        // rejected review never silently overwrote it.
        Flashcard persisted = flashcardRepository.findById(cardId).orElseThrow();
        assertThat(persisted.getLastQuality()).isEqualTo((short) 4);
    }

    private String currentUserId(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<MeResponse> me = restTemplate.exchange("/api/v1/me", HttpMethod.GET, new HttpEntity<>(headers),
                MeResponse.class);
        return me.getBody().id().toString();
    }

    private UUID uploadAndIngest(String accessToken) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "ingest-for-flashcards-" + System.nanoTime());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
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
        for (int i = 0; i < 300; i++) {
            jobDispatcher.pollOnce();
            AiJob job = aiJobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED) {
                return job;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Job did not reach a terminal state in time");
    }

    private String registerAndLogin() {
        String email = "flashcard" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Flashcard Test User", (short) 2000),
                Object.class);
        ResponseEntity<AccessTokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        return login.getBody().accessToken();
    }
}
