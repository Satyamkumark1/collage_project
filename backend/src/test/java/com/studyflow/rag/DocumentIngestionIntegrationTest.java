package com.studyflow.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.repo.AiJobRepository;
import com.studyflow.jobs.service.JobDispatcher;
import com.studyflow.library.dto.DocumentResponse;
import com.studyflow.library.dto.UploadResponse;
import com.studyflow.library.repo.DocumentRepository;
import com.studyflow.rag.repo.DocumentChunkRepository;
import com.studyflow.support.DatabaseCleanerExtension;
import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * End-to-end: real upload -> real DocumentIngestHandler run (via the real, Spring-wired
 * JobDispatcher, triggered manually since background polling is disabled in tests) -> real
 * Voyage embeddings call. Costs a small number of real Voyage tokens per run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class DocumentIngestionIntegrationTest {

    private static final String SAMPLE_TEXT = """
            # Normalization in Databases

            Database normalization is the process of structuring a relational database to reduce
            data redundancy and improve data integrity. The most common forms are 1NF, 2NF, 3NF,
            and BCNF, each building on the constraints of the previous form.

            ## First Normal Form

            A table is in first normal form if every column holds atomic, indivisible values and
            each record is unique. Repeating groups are not allowed under 1NF.

            ## Boyce-Codd Normal Form

            BCNF is a stricter version of 3NF. A table is in BCNF if, for every one of its
            non-trivial functional dependencies X -> Y, X is a superkey. It resolves anomalies
            that 3NF does not catch when a table has multiple overlapping candidate keys.
            """;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JobDispatcher jobDispatcher;
    @Autowired
    private AiJobRepository aiJobRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private DocumentChunkRepository chunkRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void uploadedTextFileReachesReadyWithRealChunksAndEmbeddings() throws InterruptedException {
        String accessToken = registerAndLogin();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "ingest-test-" + System.nanoTime());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(SAMPLE_TEXT.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "normalization.md";
            }
        });

        ResponseEntity<UploadResponse> uploadResponse = restTemplate.postForEntity("/api/v1/documents",
                new HttpEntity<>(body, headers), UploadResponse.class);
        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID documentId = uploadResponse.getBody().documentId();
        UUID jobId = uploadResponse.getBody().jobId();
        assertThat(jobId).isNotNull();

        // Background polling is disabled in tests; drive the real dispatcher/handler manually.
        UUID claimed = jobDispatcher.pollOnce();
        assertThat(claimed).isEqualTo(jobId);

        AiJob finishedJob = awaitJobTerminal(jobId);
        assertThat(finishedJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(accessToken);
        ResponseEntity<DocumentResponse> documentResponse = restTemplate.exchange("/api/v1/documents/" + documentId,
                HttpMethod.GET, new HttpEntity<>(authHeaders), DocumentResponse.class);
        assertThat(documentResponse.getBody().status()).isEqualTo("READY");
        assertThat(documentResponse.getBody().charCount()).isGreaterThan(0);

        var chunks = chunkRepository.findByDocumentIdAndOwnerIdOrderByChunkIndexAsc(documentId,
                finishedJob.getOwnerId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getContent()).isNotBlank();

        Integer embeddingCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunk_embeddings WHERE document_id = ?", Integer.class, documentId);
        assertThat(embeddingCount).isEqualTo(chunks.size());

        List<Integer> dimensions = jdbcTemplate.query(
                "SELECT vector_dims(embedding) AS dims FROM chunk_embeddings WHERE document_id = ?",
                (rs, rowNum) -> rs.getInt("dims"), documentId);
        assertThat(dimensions).allMatch(d -> d == 1024);
    }

    private AiJob awaitJobTerminal(UUID jobId) throws InterruptedException {
        // Real Voyage API round-trip; generous bound to absorb normal network latency variance.
        for (int i = 0; i < 150; i++) {
            AiJob job = aiJobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED) {
                return job;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Ingest job did not reach a terminal state in time");
    }

    private String registerAndLogin() {
        String email = "ingest" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Ingest User", (short) 2000), Object.class);
        ResponseEntity<AccessTokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        return login.getBody().accessToken();
    }
}
