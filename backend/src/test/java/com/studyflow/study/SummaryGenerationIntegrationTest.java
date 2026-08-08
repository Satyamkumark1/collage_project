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
import com.studyflow.study.dto.SummaryJobResponse;
import com.studyflow.study.dto.SummaryResponse;
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

/**
 * End-to-end with real Groq + Voyage calls: upload -> real ingest -> real (grounded) summary.
 * Costs a small number of real tokens on both providers per run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class SummaryGenerationIntegrationTest {

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

    @Test
    void endToEndUploadIngestAndSummarizeProducesGroundedCitations() throws InterruptedException {
        String accessToken = registerAndLogin();
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(accessToken);

        UUID documentId = uploadAndIngest(accessToken);

        // Requesting a summary before the document is READY must fail with DOCUMENT_NOT_READY —
        // exercised implicitly by ingesting first above; here we go straight to the happy path
        // plus a dedicated not-ready check against a second, still-uploading document.
        HttpHeaders summaryHeaders = new HttpHeaders();
        summaryHeaders.setBearerAuth(accessToken);
        summaryHeaders.add("Idempotency-Key", "summary-test-" + System.nanoTime());
        ResponseEntity<SummaryJobResponse> summaryJobResponse = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/summaries", HttpMethod.POST,
                new HttpEntity<>(summaryHeaders), SummaryJobResponse.class);
        assertThat(summaryJobResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID jobId = summaryJobResponse.getBody().jobId();

        UUID claimed = jobDispatcher.pollOnce();
        assertThat(claimed).isEqualTo(jobId);
        AiJob finishedJob = awaitJobTerminal(jobId);
        assertThat(finishedJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        ResponseEntity<SummaryResponse[]> listResponse = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/summaries", HttpMethod.GET, new HttpEntity<>(authHeaders),
                SummaryResponse[].class);
        assertThat(listResponse.getBody()).hasSize(1);
        SummaryResponse summary = listResponse.getBody()[0];
        assertThat(summary.contentMd()).isNotBlank();
        assertThat(summary.model()).isNotBlank();
        assertThat(summary.citations().isArray()).isTrue();
        assertThat(summary.citations()).isNotEmpty();

        ResponseEntity<SummaryResponse> getResponse = restTemplate.exchange("/api/v1/summaries/" + summary.id(),
                HttpMethod.GET, new HttpEntity<>(authHeaders), SummaryResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().id()).isEqualTo(summary.id());
    }

    @Test
    void requestingASummaryBeforeIngestionFinishesIsRejected() {
        String accessToken = registerAndLogin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "not-ready-" + System.nanoTime());

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

        // Deliberately do NOT run the ingest job — document stays UPLOADED.
        HttpHeaders summaryHeaders = new HttpHeaders();
        summaryHeaders.setBearerAuth(accessToken);
        summaryHeaders.add("Idempotency-Key", "not-ready-summary-" + System.nanoTime());
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/documents/" + documentId + "/summaries",
                HttpMethod.POST, new HttpEntity<>(summaryHeaders), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("DOCUMENT_NOT_READY");
    }

    private UUID uploadAndIngest(String accessToken) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "ingest-for-summary-" + System.nanoTime());

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
        // Real Groq/Voyage API round-trips; generous bound to absorb normal network latency.
        for (int i = 0; i < 150; i++) {
            AiJob job = aiJobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED) {
                return job;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Job did not reach a terminal state in time");
    }

    private String registerAndLogin() {
        String email = "summary" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Summary User", (short) 2000), Object.class);
        ResponseEntity<AccessTokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        return login.getBody().accessToken();
    }
}
