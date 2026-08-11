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
import com.studyflow.rag.repo.DocumentChunkRepository;
import com.studyflow.support.DatabaseCleanerExtension;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
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
 * End-to-end for the DOCX/PPTX ingestion checkpoint's genuinely novel interaction: PPTX's
 * multi-ParsedPage shape flowing through chunking/citation page numbers, real Postgres + real
 * Voyage embeddings (see DocumentIngestionIntegrationTest, which already proves the whole-doc,
 * one-page shape for TXT/MD/DOCX). Costs a small number of real Voyage tokens per run — DOCX
 * isn't duplicated here since its pipeline shape is identical to the already-proven TXT/MD path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class PptxIngestionIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JobDispatcher jobDispatcher;
    @Autowired
    private AiJobRepository aiJobRepository;
    @Autowired
    private DocumentChunkRepository chunkRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void uploadedPptxReachesReadyWithRealChunksAndEmbeddings() throws IOException, InterruptedException {
        String accessToken = registerAndLogin();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "pptx-ingest-test-" + System.nanoTime());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(twoSlideDeck()) {
            @Override
            public String getFilename() {
                return "os-scheduling.pptx";
            }
        });

        ResponseEntity<UploadResponse> uploadResponse = restTemplate.postForEntity("/api/v1/documents",
                new HttpEntity<>(body, headers), UploadResponse.class);
        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID documentId = uploadResponse.getBody().documentId();
        UUID jobId = uploadResponse.getBody().jobId();
        assertThat(jobId).isNotNull();

        UUID claimed = jobDispatcher.pollOnce();
        assertThat(claimed).isEqualTo(jobId);

        AiJob finishedJob = awaitJobTerminal(jobId);
        assertThat(finishedJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(accessToken);
        ResponseEntity<DocumentResponse> documentResponse = restTemplate.exchange("/api/v1/documents/" + documentId,
                HttpMethod.GET, new HttpEntity<>(authHeaders), DocumentResponse.class);
        assertThat(documentResponse.getBody().status()).isEqualTo("READY");
        assertThat(documentResponse.getBody().fileType()).isEqualTo("PPTX");
        assertThat(documentResponse.getBody().charCount()).isGreaterThan(0);

        var chunks = chunkRepository.findByDocumentIdAndOwnerIdOrderByChunkIndexAsc(documentId,
                finishedJob.getOwnerId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getContent()).isNotBlank();
        // Both slides' text made it into the chunked content — proves the multi-ParsedPage shape
        // (one per slide) actually flows through normalization/chunking, not just slide 1.
        String allChunkText = chunks.stream().map(c -> c.getContent()).reduce("", String::concat);
        assertThat(allChunkText).contains("preemptive scheduling").contains("Round robin");

        Integer embeddingCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunk_embeddings WHERE document_id = ?", Integer.class, documentId);
        assertThat(embeddingCount).isEqualTo(chunks.size());

        List<Integer> dimensions = jdbcTemplate.query(
                "SELECT vector_dims(embedding) AS dims FROM chunk_embeddings WHERE document_id = ?",
                (rs, rowNum) -> rs.getInt("dims"), documentId);
        assertThat(dimensions).allMatch(d -> d == 1024);
    }

    private byte[] twoSlideDeck() throws IOException {
        try (XMLSlideShow slideShow = new XMLSlideShow()) {
            addSlide(slideShow, "Operating systems use preemptive scheduling to interrupt a running "
                    + "process so the CPU can be reassigned to another process, unlike cooperative "
                    + "scheduling where a process must voluntarily yield control back to the scheduler.");
            addSlide(slideShow, "Round robin scheduling assigns each process a fixed time slice, or "
                    + "quantum, in a cyclic order — a simple, fair, preemptive algorithm well suited to "
                    + "time-sharing systems where interactive response time matters.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            slideShow.write(out);
            return out.toByteArray();
        }
    }

    private void addSlide(XMLSlideShow slideShow, String text) {
        XSLFSlide slide = slideShow.createSlide();
        XSLFTextBox textBox = slide.createTextBox();
        textBox.setText(text);
    }

    private AiJob awaitJobTerminal(UUID jobId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
        while (Instant.now().isBefore(deadline)) {
            jobDispatcher.pollOnce();
            AiJob job = aiJobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED) {
                return job;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Ingest job did not reach a terminal state in time");
    }

    private String registerAndLogin() {
        String email = "pptx-ingest" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Pptx Ingest User", (short) 2000), Object.class);
        ResponseEntity<AccessTokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        return login.getBody().accessToken();
    }
}
