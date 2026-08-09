package com.studyflow.study;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.repo.AiJobRepository;
import com.studyflow.jobs.service.JobDispatcher;
import com.studyflow.library.dto.UploadResponse;
import com.studyflow.study.dto.QuestionResponse;
import com.studyflow.study.dto.QuestionSetJobResponse;
import com.studyflow.study.dto.QuestionSetRequest;
import com.studyflow.study.dto.QuestionSetResponse;
import com.studyflow.support.DatabaseCleanerExtension;
import java.nio.charset.StandardCharsets;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class McqGenerationIntegrationTest {

    private static final String SAMPLE_TEXT = """
            # ACID Properties in DBMS

            ACID stands for Atomicity, Consistency, Isolation, and Durability. These four
            properties guarantee that database transactions are processed reliably even in the
            presence of errors, power failures, or concurrent access.

            ## Atomicity

            Atomicity means a transaction is all-or-nothing: either every operation in it
            succeeds, or none of them take effect. If a transaction transferring money between two
            bank accounts fails halfway through, atomicity ensures neither account is debited or
            credited — the whole operation rolls back.

            ## Consistency

            Consistency means a transaction takes the database from one valid state to another,
            preserving all defined rules such as constraints, cascades, and triggers. A
            transaction that would violate a constraint is rejected entirely.

            ## Isolation

            Isolation ensures that concurrently executing transactions do not interfere with each
            other's intermediate, uncommitted state. Different isolation levels (read uncommitted,
            read committed, repeatable read, serializable) trade off consistency guarantees
            against concurrency performance.

            ## Durability

            Durability means that once a transaction commits, its changes survive any subsequent
            system crash, typically by writing to a write-ahead log before acknowledging the
            commit to the client.
            """;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JobDispatcher jobDispatcher;
    @Autowired
    private AiJobRepository aiJobRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void endToEndUploadIngestAndGenerateMcqsProducesValidQuestions() throws InterruptedException {
        String accessToken = registerAndLogin();
        UUID documentId = uploadAndIngest(accessToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", "mcq-test-" + System.nanoTime());
        ResponseEntity<QuestionSetJobResponse> response = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/question-sets", HttpMethod.POST,
                new HttpEntity<>(new QuestionSetRequest(10), headers), QuestionSetJobResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID jobId = response.getBody().jobId();

        UUID claimed = jobDispatcher.pollOnce();
        assertThat(claimed).isEqualTo(jobId);
        AiJob finishedJob = awaitJobTerminal(jobId);
        assertThat(finishedJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth(accessToken);
        ResponseEntity<QuestionSetResponse[]> listResponse = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/question-sets", HttpMethod.GET, new HttpEntity<>(getHeaders),
                QuestionSetResponse[].class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotEmpty();
        QuestionSetResponse questionSet = listResponse.getBody()[0];
        assertThat(questionSet.requestedCount()).isEqualTo(10);
        assertThat(questionSet.generatedCount()).isGreaterThan(0).isLessThanOrEqualTo(10);

        ResponseEntity<QuestionResponse[]> questionsResponse = restTemplate.exchange(
                "/api/v1/question-sets/" + questionSet.id() + "/questions", HttpMethod.GET,
                new HttpEntity<>(getHeaders), QuestionResponse[].class);
        assertThat(questionsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        QuestionResponse[] questions = questionsResponse.getBody();
        assertThat(questions).hasSize(questionSet.generatedCount());
        for (QuestionResponse question : questions) {
            assertThat(question.stem()).isNotBlank();
            assertThat(question.options().size()).isEqualTo(4);
            assertThat(question.correctIndex()).isBetween(0, 3);
            assertThat(question.explanation()).isNotBlank();
            assertThat(question.difficulty()).isIn("EASY", "MEDIUM", "HARD");
            assertThat(question.bloomLevel()).isIn("REMEMBER", "UNDERSTAND", "APPLY", "ANALYZE");
            assertThat(question.citations().isArray()).isTrue();
            assertThat(question.citations()).isNotEmpty();
        }
    }

    @Test
    void requestingAnInvalidCountIsRejected() {
        String accessToken = registerAndLogin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", "mcq-bad-count-" + System.nanoTime());

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/documents/" + UUID.randomUUID()
                + "/question-sets", HttpMethod.POST, new HttpEntity<>(new QuestionSetRequest(7), headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = objectMapper.readTree(response.getBody());
        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void requestingMcqsBeforeIngestionFinishesIsRejected() {
        String accessToken = registerAndLogin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "not-ready-mcq-" + System.nanoTime());

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

        HttpHeaders mcqHeaders = new HttpHeaders();
        mcqHeaders.setBearerAuth(accessToken);
        mcqHeaders.setContentType(MediaType.APPLICATION_JSON);
        mcqHeaders.add("Idempotency-Key", "mcq-not-ready-" + System.nanoTime());
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/documents/" + documentId + "/question-sets",
                HttpMethod.POST, new HttpEntity<>(new QuestionSetRequest(10), mcqHeaders), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = objectMapper.readTree(response.getBody());
        assertThat(problem.get("code").asString()).isEqualTo("DOCUMENT_NOT_READY");
    }

    private UUID uploadAndIngest(String accessToken) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "ingest-for-mcq-" + System.nanoTime());

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
        String email = "mcq" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "MCQ Test User", (short) 2000), Object.class);
        ResponseEntity<AccessTokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        return login.getBody().accessToken();
    }
}
