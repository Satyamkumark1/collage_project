package com.studyflow.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.ai.domain.AiCall;
import com.studyflow.ai.domain.AiOutcome;
import com.studyflow.ai.repo.AiCallRepository;
import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.MeResponse;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.repo.AiJobRepository;
import com.studyflow.jobs.service.JobDispatcher;
import com.studyflow.library.dto.UploadResponse;
import com.studyflow.rag.service.ChunkQueryService;
import com.studyflow.rag.service.ChunkQueryService.ChunkView;
import com.studyflow.rag.service.RetrievalService;
import com.studyflow.rag.service.RetrievalService.RetrievalResult;
import com.studyflow.study.domain.KeyPoint;
import com.studyflow.study.domain.Question;
import com.studyflow.study.domain.QuestionSet;
import com.studyflow.study.dto.KeyPointJobResponse;
import com.studyflow.study.dto.QuestionSetJobResponse;
import com.studyflow.study.dto.QuestionSetRequest;
import com.studyflow.study.repo.KeyPointRepository;
import com.studyflow.study.repo.QuestionRepository;
import com.studyflow.study.repo.QuestionSetRepository;
import com.studyflow.support.DatabaseCleanerExtension;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Eval harness — runs the real ingestion + key-points + MCQ pipeline over the documents in
 * {@code eval/documents/} against real Postgres/Groq/Voyage, and reports the 5 metrics named in
 * specs/08-ai-layer.md's "Eval harness" section. Excluded from the default {@code mvn test} run
 * (see pom.xml's surefire {@code excludedGroups}) — run deliberately with
 * {@code -Dgroups=eval -DexcludedGroups=}. Thresholds are proposals (docs/DECISIONS.md), not
 * enforced here; CI gating is deferred to Phase 5 (docs/status/phase-3.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
@Tag("eval")
class EvalHarnessRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalHarnessRunner.class);
    private static final Pattern WORD_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final double LEXICAL_OVERLAP_THRESHOLD = 0.15;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JobDispatcher jobDispatcher;
    @Autowired
    private AiJobRepository aiJobRepository;
    @Autowired
    private AiCallRepository aiCallRepository;
    @Autowired
    private KeyPointRepository keyPointRepository;
    @Autowired
    private QuestionSetRepository questionSetRepository;
    @Autowired
    private QuestionRepository questionRepository;
    @Autowired
    private ChunkQueryService chunkQueryService;
    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void runEvalSuite() throws InterruptedException {
        List<EvalCase> cases = EvalDocumentFixtures.loadAll(objectMapper);
        assertThat(cases).isNotEmpty();

        String accessToken = registerAndLogin();
        UUID ownerId = currentUserId(accessToken);
        EvalReport report = new EvalReport();

        for (EvalCase evalCase : cases) {
            UUID documentId = uploadAndIngest(accessToken, evalCase);
            Set<String> validChunkIds = new HashSet<>();
            for (ChunkView chunk : chunkQueryService.findOrderedChunks(documentId, ownerId)) {
                validChunkIds.add(chunk.id().toString());
            }

            UUID keyPointsJobId = requestKeyPoints(accessToken, documentId);
            AiJob keyPointsJob = awaitJobTerminal(keyPointsJobId);
            report.recordJobLatency(latencyMs(keyPointsJob));
            if (keyPointsJob.getStatus() == JobStatus.SUCCEEDED) {
                gradeKeyPoints(documentId, ownerId, validChunkIds, report);
            } else {
                report.addNote(evalCase.documentSlug() + ": key-points job failed (" + keyPointsJob.getErrorCode()
                        + ")");
            }

            UUID mcqJobId = requestMcqs(accessToken, documentId);
            AiJob mcqJob = awaitJobTerminal(mcqJobId);
            report.recordJobLatency(latencyMs(mcqJob));
            if (mcqJob.getStatus() == JobStatus.SUCCEEDED) {
                gradeQuestions(documentId, ownerId, validChunkIds, report);
            } else {
                report.addNote(evalCase.documentSlug() + ": MCQ job failed (" + mcqJob.getErrorCode() + ")");
            }

            for (EvalCase.RetrievalProbe probe : evalCase.retrievalProbes()) {
                Optional<RetrievalResult> result = retrieveWithRetry(documentId, ownerId, probe.query());
                if (result.isEmpty()) {
                    report.addNote(evalCase.documentSlug() + ": retrieval probe '" + probe.query()
                            + "' skipped — Voyage stayed rate-limited after retries (see docs/DECISIONS.md's "
                            + "3 RPM cap entry)");
                    continue;
                }
                boolean found = result.get().chunks().stream()
                        .anyMatch(chunk -> chunk.content().toLowerCase(Locale.ROOT)
                                .contains(probe.expectedKeyword().toLowerCase(Locale.ROOT)));
                report.recordRetrievalProbe(found);
            }
        }

        List<AiCall> ownerCalls = aiCallRepository.findAll().stream()
                .filter(call -> call.getOwnerId().equals(ownerId))
                .toList();
        long okOrRepaired = ownerCalls.stream()
                .filter(call -> call.getOutcome() == AiOutcome.OK || call.getOutcome() == AiOutcome.REPAIRED)
                .count();
        report.recordSchemaCalls((int) okOrRepaired, ownerCalls.size());

        String rendered = report.render();
        log.info("\n{}", rendered);
        assertThat(cases).hasSize(3);
    }

    private void gradeKeyPoints(UUID documentId, UUID ownerId, Set<String> validChunkIds, EvalReport report) {
        Optional<KeyPoint> latest = keyPointRepository.findFirstByDocumentIdAndOwnerIdOrderByCreatedAtDesc(documentId,
                ownerId);
        if (latest.isEmpty()) {
            return;
        }
        List<KeyPoint> keyPoints = keyPointRepository
                .findByDocumentIdAndOwnerIdAndJobIdOrderBySortOrderAsc(documentId, ownerId, latest.get().getJobId());
        for (KeyPoint keyPoint : keyPoints) {
            gradeCitations(keyPoint.getCitationsJson(), keyPoint.getLabel() + " " + keyPoint.getContentMd(),
                    validChunkIds, report);
        }
    }

    private void gradeQuestions(UUID documentId, UUID ownerId, Set<String> validChunkIds, EvalReport report) {
        List<QuestionSet> sets = questionSetRepository.findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(documentId,
                ownerId);
        if (sets.isEmpty()) {
            return;
        }
        List<Question> questions = questionRepository
                .findByQuestionSetIdAndOwnerIdOrderBySortOrderAsc(sets.get(0).getId(), ownerId);
        int valid = 0;
        for (Question question : questions) {
            JsonNode options = objectMapper.readTree(question.getOptionsJson());
            boolean structurallyValid = options.isArray() && options.size() == 4
                    && question.getCorrectIndex() >= 0 && question.getCorrectIndex() <= 3
                    && !question.getStem().isBlank() && !question.getExplanation().isBlank();
            if (structurallyValid) {
                valid++;
            }
            gradeCitations(question.getCitationsJson(), question.getStem() + " " + question.getExplanation(),
                    validChunkIds, report);
        }
        report.recordMcqValidity(valid, questions.size());
    }

    private void gradeCitations(String citationsJson, String citingText, Set<String> validChunkIds,
            EvalReport report) {
        JsonNode citations = objectMapper.readTree(citationsJson);
        if (!citations.isArray()) {
            return;
        }
        for (JsonNode citation : citations) {
            JsonNode chunkIdNode = citation.get("chunkId");
            String chunkId = chunkIdNode == null ? null : chunkIdNode.asString();
            boolean structurallyGrounded = chunkId != null && validChunkIds.contains(chunkId);
            boolean lexicallyGrounded = structurallyGrounded
                    && lexicalOverlap(citingText, chunkTextFor(chunkId)) >= LEXICAL_OVERLAP_THRESHOLD;
            report.recordCitationGroundedness(structurallyGrounded ? 1 : 0, lexicallyGrounded ? 1 : 0, 1);
        }
    }

    private final Map<String, String> chunkContentCache = new HashMap<>();

    private String chunkTextFor(String chunkId) {
        return chunkId == null ? "" : chunkContentCache.getOrDefault(chunkId, "");
    }

    private double lexicalOverlap(String citingText, String chunkContent) {
        if (chunkContent.isEmpty()) {
            return 0.0;
        }
        Set<String> citingWords = new HashSet<>(List.of(WORD_SPLIT.split(citingText.toLowerCase(Locale.ROOT))));
        Set<String> chunkWords = new HashSet<>(List.of(WORD_SPLIT.split(chunkContent.toLowerCase(Locale.ROOT))));
        citingWords.removeIf(String::isBlank);
        if (citingWords.isEmpty()) {
            return 0.0;
        }
        long overlap = citingWords.stream().filter(chunkWords::contains).count();
        return (double) overlap / citingWords.size();
    }

    // This account's Voyage tier is hard-capped at 3 requests/minute (see docs/DECISIONS.md) —
    // a retrieval probe embeds its query via Voyage, and running 3 documents' worth of
    // ingestion + probes back-to-back reliably exceeds that cap. Same "keep retrying with
    // spacing" posture the job engine's own retry/backoff already uses for job-based Voyage
    // calls; this is a direct synchronous call instead, so it gets its own small retry loop
    // rather than silently failing the whole harness run over a known, documented rate cap.
    private Optional<RetrievalResult> retrieveWithRetry(UUID documentId, UUID ownerId, String query)
            throws InterruptedException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return Optional.of(retrievalService.retrieve(documentId, ownerId, query));
            } catch (RuntimeException e) {
                if (attempt == 3) {
                    return Optional.empty();
                }
                Thread.sleep(20_000);
            }
        }
        return Optional.empty();
    }

    private long latencyMs(AiJob job) {
        if (job.getStartedAt() == null || job.getFinishedAt() == null) {
            return 0;
        }
        return job.getFinishedAt().toEpochMilli() - job.getStartedAt().toEpochMilli();
    }

    private UUID requestKeyPoints(String accessToken, UUID documentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.add("Idempotency-Key", "eval-keypoints-" + System.nanoTime());
        ResponseEntity<KeyPointJobResponse> response = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/key-points", HttpMethod.POST, new HttpEntity<>(headers),
                KeyPointJobResponse.class);
        return response.getBody().jobId();
    }

    private UUID requestMcqs(String accessToken, UUID documentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", "eval-mcq-" + System.nanoTime());
        ResponseEntity<QuestionSetJobResponse> response = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/question-sets", HttpMethod.POST,
                new HttpEntity<>(new QuestionSetRequest(10), headers), QuestionSetJobResponse.class);
        return response.getBody().jobId();
    }

    private UUID uploadAndIngest(String accessToken, EvalCase evalCase) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "eval-ingest-" + evalCase.documentSlug() + "-" + System.nanoTime());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(evalCase.content().getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return evalCase.sourceFile();
            }
        });
        ResponseEntity<UploadResponse> uploadResponse = restTemplate.postForEntity("/api/v1/documents",
                new HttpEntity<>(body, headers), UploadResponse.class);
        UUID documentId = uploadResponse.getBody().documentId();
        UUID ingestJobId = uploadResponse.getBody().jobId();

        AiJob ingestJob = awaitJobTerminal(ingestJobId);
        assertThat(ingestJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        for (ChunkView chunk : chunkQueryService.findOrderedChunks(documentId,
                UUID.fromString(currentUserIdString(accessToken)))) {
            chunkContentCache.put(chunk.id().toString(), chunk.content());
        }
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
        String email = "eval" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Eval Harness User", (short) 2000),
                Object.class);
        ResponseEntity<AccessTokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        return login.getBody().accessToken();
    }

    private UUID currentUserId(String accessToken) {
        return UUID.fromString(currentUserIdString(accessToken));
    }

    private String currentUserIdString(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<MeResponse> response = restTemplate.exchange("/api/v1/me", HttpMethod.GET,
                new HttpEntity<>(headers), MeResponse.class);
        return response.getBody().id().toString();
    }
}
