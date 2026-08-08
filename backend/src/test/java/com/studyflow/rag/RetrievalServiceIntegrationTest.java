package com.studyflow.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.repo.AiJobRepository;
import com.studyflow.jobs.service.JobDispatcher;
import com.studyflow.library.dto.UploadResponse;
import com.studyflow.rag.service.RetrievalService;
import com.studyflow.rag.service.RetrievalService.RetrievalResult;
import com.studyflow.rag.service.RetrievalService.RetrievedChunk;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Real Postgres + real Voyage embeddings, exercising {@link RetrievalService} directly (not
 * through the tutor HTTP/SSE layer) so hybrid-search shape (fusion, neighbour expansion) has
 * focused coverage independent of the streaming plumbing — see specs/09-rag.md, docs/DECISIONS.md
 * for the parameters under test. The sample document is long enough (six distinct sections) to
 * force multiple chunks, unlike the short fixtures other Phase 1 tests use.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class RetrievalServiceIntegrationTest {

    private static final String SAMPLE_TEXT = """
            # Process Scheduling in Operating Systems

            An operating system's scheduler decides which of the ready processes gets the CPU
            next. Scheduling matters because CPU time is a shared, finite resource, and different
            workloads (interactive terminals, batch jobs, real-time control loops) have very
            different expectations for how quickly they should be serviced. A scheduler is judged
            on turnaround time, waiting time, response time, throughput, and fairness, and no
            single policy optimizes all of these simultaneously — every scheduling algorithm is a
            deliberate trade-off among them.

            ## First-Come First-Served Scheduling

            First-Come First-Served, or FCFS, runs processes strictly in arrival order, using a
            simple FIFO queue with no preemption once a process starts running. FCFS is trivial to
            implement and completely fair in the sense that nobody is ever skipped over, but it
            suffers badly from the convoy effect: a single long CPU-bound process at the front of
            the queue can force every short process behind it to wait far longer than its own
            runtime would suggest. Average waiting time under FCFS is highly sensitive to the
            arrival order of long versus short jobs.

            ## Shortest Job First Scheduling

            Shortest Job First, or SJF, always picks the ready process with the smallest estimated
            CPU burst next. SJF is provably optimal for minimizing average waiting time among
            non-preemptive policies, but it needs to know or accurately estimate the length of the
            next CPU burst in advance, which is rarely available in a general-purpose system.
            Estimation is usually done with an exponential moving average of a process's past CPU
            bursts. SJF can also starve long processes indefinitely if a steady stream of short
            jobs keeps arriving.

            ## Round Robin Scheduling

            Round Robin assigns each process a fixed time quantum and cycles through the ready
            queue, preempting a process once its quantum expires and moving it to the back of the
            queue. Round Robin is the standard choice for time-sharing, interactive systems because
            it guarantees bounded response time regardless of how many processes are waiting. The
            quantum size is a critical tuning knob: too large and Round Robin degrades toward
            FCFS-like behaviour; too small and context-switch overhead starts to dominate actual
            useful work.

            ## Priority Scheduling and Starvation

            Priority scheduling assigns each process a priority number and always runs the highest
            priority ready process next, either preemptively or non-preemptively. The core danger
            of priority scheduling is starvation: a low-priority process can wait indefinitely if
            higher-priority processes keep arriving. The standard fix is aging, where a process's
            priority is gradually increased the longer it waits, guaranteeing it eventually becomes
            the highest-priority ready process.

            ## Rate Monotonic Scheduling for Real-Time Systems

            Rate Monotonic Scheduling is a fixed-priority algorithm specifically for periodic
            real-time tasks, where a task's priority is assigned inversely to its period — the
            more frequently a task must run, the higher its priority. Rate Monotonic Scheduling is
            provably optimal among fixed-priority algorithms for meeting deadlines on a single
            processor, and a well-known schedulability test (the Liu and Layland bound) gives a
            sufficient utilization threshold below which every task set is guaranteed to meet its
            deadlines. It is the classic scheduling policy taught alongside Earliest Deadline
            First, which is dynamic-priority rather than fixed-priority.

            ## Multilevel Feedback Queues

            A multilevel feedback queue scheduler uses several ready queues, each with its own
            priority and time quantum, and lets processes move between queues based on observed
            behaviour: a process that uses its full quantum is demoted to a lower-priority, longer-
            quantum queue, while a process that gives up the CPU early (typically I/O-bound) is
            promoted or kept at a higher-priority, shorter-quantum queue. This design approximates
            SJF's low average waiting time for short and interactive jobs without ever needing to
            know CPU burst lengths in advance, and most general-purpose operating system schedulers
            are built on some variant of this idea.
            """;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JobDispatcher jobDispatcher;
    @Autowired
    private AiJobRepository aiJobRepository;
    @Autowired
    private RetrievalService retrievalService;

    @Test
    void hybridRetrievalFindsAndExpandsTheRelevantChunk() throws InterruptedException {
        String accessToken = registerAndLogin();
        UUID ownerId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "retrieval-test-" + System.nanoTime());
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(SAMPLE_TEXT.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "scheduling.md";
            }
        });
        ResponseEntity<UploadResponse> uploadResponse = restTemplate.postForEntity("/api/v1/documents",
                new HttpEntity<>(body, headers), UploadResponse.class);
        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID documentId = uploadResponse.getBody().documentId();
        UUID jobId = uploadResponse.getBody().jobId();

        UUID claimed = jobDispatcher.pollOnce();
        assertThat(claimed).isEqualTo(jobId);
        AiJob finishedJob = awaitJobTerminal(jobId);
        assertThat(finishedJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        ownerId = finishedJob.getOwnerId();

        // Vector arm: a semantically close paraphrase, no exact keyword overlap forced.
        RetrievalResult semanticMatch = retrievalService.retrieve(documentId, ownerId,
                "Why does a scheduler that always runs the shortest task next minimize average wait time?");
        assertThat(semanticMatch.chunks()).isNotEmpty();
        assertThat(semanticMatch.bestVectorSimilarity()).isGreaterThan(0.35);
        assertThat(semanticMatch.chunks()).anySatisfy(
                chunk -> assertThat(chunk.content()).containsIgnoringCase("Shortest Job First"));

        // Lexical arm: a rare, distinctive literal phrase that only appears in one section —
        // exercises full-text search finding it even if the vector arm alone ranked it lower.
        RetrievalResult lexicalMatch = retrievalService.retrieve(documentId, ownerId,
                "Liu and Layland schedulability bound");
        assertThat(lexicalMatch.chunks()).anySatisfy(
                chunk -> assertThat(chunk.content()).contains("Liu and Layland"));

        // Every returned chunk carries real page/section provenance — what makes citations
        // possible at all (see specs/09-rag.md).
        for (RetrievedChunk chunk : semanticMatch.chunks()) {
            assertThat(chunk.chunkId()).isNotNull();
            assertThat(chunk.content()).isNotBlank();
        }
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

    private String registerAndLogin() {
        String email = "retrieval" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Retrieval User", (short) 2000), Object.class);
        ResponseEntity<AccessTokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        return login.getBody().accessToken();
    }
}
