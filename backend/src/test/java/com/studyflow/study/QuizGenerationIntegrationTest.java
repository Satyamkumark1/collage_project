package com.studyflow.study;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.MeResponse;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.repo.AiJobRepository;
import com.studyflow.jobs.service.JobDispatcher;
import com.studyflow.library.dto.UploadResponse;
import com.studyflow.study.domain.Question;
import com.studyflow.study.dto.AnswerRequest;
import com.studyflow.study.dto.AnswerResponse;
import com.studyflow.study.dto.QuizAttemptResponse;
import com.studyflow.study.dto.QuizJobResponse;
import com.studyflow.study.dto.QuizQuestionResponse;
import com.studyflow.study.dto.QuizRequest;
import com.studyflow.study.dto.QuizResponse;
import com.studyflow.study.dto.QuizResultResponse;
import com.studyflow.study.repo.QuestionRepository;
import com.studyflow.support.DatabaseCleanerExtension;
import java.math.BigDecimal;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real Postgres + real Groq, mirroring {@link McqGenerationIntegrationTest}'s shape — a quiz
 * build reuses MCQ generation unchanged, so this test focuses on what's new: mode-driven
 * timing/scoring config, the answer-key-withheld-until-submit contract, incremental answer saves
 * (including clearing one), and the server-authoritative EXAM deadline. See docs/DECISIONS.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class QuizGenerationIntegrationTest {

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
    private QuestionRepository questionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void examModeWithholdsAnswerKeyAppliesNegativeMarkingAndScoresCorrectly() throws InterruptedException {
        String accessToken = registerAndLogin();
        UUID ownerId = UUID.fromString(currentUserId(accessToken));
        UUID documentId = uploadAndIngest(accessToken);

        QuizResponse quiz = buildQuiz(accessToken, documentId, "EXAM", 10);
        assertThat(quiz.mode()).isEqualTo("EXAM");
        assertThat(quiz.timeLimitSeconds()).isEqualTo(quiz.questionCount() * 90);
        assertThat(quiz.negativeMarkingFraction()).isEqualByComparingTo("0.25");

        List<Question> groundTruth = questionRepository
                .findByQuestionSetIdAndOwnerIdOrderBySortOrderAsc(quiz.questionSetId(), ownerId);
        QuizQuestionResponse[] takeableQuestions = questionsFor(accessToken, quiz.id());
        assertThat(groundTruth).hasSameSizeAs(takeableQuestions);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<QuizAttemptResponse> startResponse = restTemplate.exchange(
                "/api/v1/quizzes/" + quiz.id() + "/attempts", HttpMethod.POST, new HttpEntity<>(headers),
                QuizAttemptResponse.class);
        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        QuizAttemptResponse attempt = startResponse.getBody();
        assertThat(attempt.status()).isEqualTo("IN_PROGRESS");
        assertThat(attempt.deadlineAt()).isNotNull();

        // Adaptive bucket sizing: partial-success generation can legitimately yield fewer than
        // requestedCount questions (same "N of M" contract as MCQs — see docs/DECISIONS.md), so
        // this never assumes a fixed count, only that at least one question exists.
        int n = groundTruth.size();
        int unansweredBucket = n >= 3 ? 1 : 0;
        int incorrectBucket = n >= 2 ? 1 : 0;
        int correctBucket = n - unansweredBucket - incorrectBucket;

        for (int i = 0; i < correctBucket; i++) {
            Question q = groundTruth.get(i);
            AnswerResponse ans = saveAnswer(accessToken, attempt.id(), takeableQuestions[i].id(),
                    (int) q.getCorrectIndex());
            // EXAM withholds correctness until submit, unlike REVISION.
            assertThat(ans.isCorrect()).isNull();
            assertThat(ans.explanation()).isNull();
        }
        if (incorrectBucket == 1) {
            Question wrongTarget = groundTruth.get(correctBucket);
            int wrongIndex = (wrongTarget.getCorrectIndex() + 1) % 4;
            saveAnswer(accessToken, attempt.id(), takeableQuestions[correctBucket].id(), wrongIndex);
        }
        if (unansweredBucket == 1) {
            // Answer, then explicitly clear it — proves the clear-an-answer path, and it should
            // end up counted as unanswered.
            int idx = correctBucket + incorrectBucket;
            Question lastQuestion = groundTruth.get(idx);
            saveAnswer(accessToken, attempt.id(), takeableQuestions[idx].id(), (int) lastQuestion.getCorrectIndex());
            AnswerResponse cleared = saveAnswer(accessToken, attempt.id(), takeableQuestions[idx].id(), null);
            assertThat(cleared.selectedIndex()).isNull();
        }

        // The answer key is never sent to the client before submit.
        ResponseEntity<String> earlyResult = restTemplate.exchange("/api/v1/quiz-attempts/" + attempt.id() + "/result",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(earlyResult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode earlyProblem = objectMapper.readTree(earlyResult.getBody());
        assertThat(earlyProblem.get("code").asString()).isEqualTo("QUIZ_ATTEMPT_NOT_SUBMITTED");

        QuizResultResponse result = submit(accessToken, attempt.id());
        assertThat(result.attempt().status()).isEqualTo("SUBMITTED");
        assertThat(result.attempt().correctCount()).isEqualTo((short) correctBucket);
        assertThat(result.attempt().incorrectCount()).isEqualTo((short) incorrectBucket);
        assertThat(result.attempt().unansweredCount()).isEqualTo((short) unansweredBucket);
        BigDecimal expectedScore = BigDecimal.valueOf(correctBucket)
                .subtract(new BigDecimal("0.25").multiply(BigDecimal.valueOf(incorrectBucket)));
        assertThat(result.attempt().score()).isEqualByComparingTo(expectedScore);
        assertThat(result.questions()).hasSize(n);
        assertThat(result.questions()).extracting(q -> q.explanation()).allMatch(e -> e != null && !e.isBlank());

        // GET result is idempotent once terminal.
        ResponseEntity<QuizResultResponse> secondResult = restTemplate.exchange(
                "/api/v1/quiz-attempts/" + attempt.id() + "/result", HttpMethod.GET, new HttpEntity<>(headers),
                QuizResultResponse.class);
        assertThat(secondResult.getBody().attempt().score()).isEqualByComparingTo(expectedScore);
    }

    @Test
    void practiceModeAppliesNoNegativeMarking() throws InterruptedException {
        String accessToken = registerAndLogin();
        UUID ownerId = UUID.fromString(currentUserId(accessToken));
        UUID documentId = uploadAndIngest(accessToken);

        QuizResponse quiz = buildQuiz(accessToken, documentId, "PRACTICE", 10);
        assertThat(quiz.negativeMarkingFraction()).isEqualByComparingTo("0.00");
        assertThat(quiz.timeLimitSeconds()).isEqualTo(quiz.questionCount() * 90);

        List<Question> groundTruth = questionRepository
                .findByQuestionSetIdAndOwnerIdOrderBySortOrderAsc(quiz.questionSetId(), ownerId);
        QuizQuestionResponse[] takeableQuestions = questionsFor(accessToken, quiz.id());
        UUID attemptId = startAttempt(accessToken, quiz.id()).id();

        int n = groundTruth.size();
        for (int i = 0; i < n - 1; i++) {
            saveAnswer(accessToken, attemptId, takeableQuestions[i].id(), (int) groundTruth.get(i).getCorrectIndex());
        }
        Question wrongTarget = groundTruth.get(n - 1);
        saveAnswer(accessToken, attemptId, takeableQuestions[n - 1].id(), (wrongTarget.getCorrectIndex() + 1) % 4);

        QuizResultResponse result = submit(accessToken, attemptId);
        assertThat(result.attempt().correctCount()).isEqualTo((short) (n - 1));
        assertThat(result.attempt().incorrectCount()).isEqualTo((short) 1);
        // No negative marking in PRACTICE — the wrong answer costs nothing.
        assertThat(result.attempt().score()).isEqualByComparingTo(BigDecimal.valueOf(n - 1));
    }

    @Test
    void revisionModeIsUntimedAndGivesImmediateFeedback() throws InterruptedException {
        String accessToken = registerAndLogin();
        UUID ownerId = UUID.fromString(currentUserId(accessToken));
        UUID documentId = uploadAndIngest(accessToken);

        QuizResponse quiz = buildQuiz(accessToken, documentId, "REVISION", 10);
        assertThat(quiz.timeLimitSeconds()).isNull();
        assertThat(quiz.negativeMarkingFraction()).isEqualByComparingTo("0.00");

        List<Question> groundTruth = questionRepository
                .findByQuestionSetIdAndOwnerIdOrderBySortOrderAsc(quiz.questionSetId(), ownerId);
        QuizQuestionResponse[] takeableQuestions = questionsFor(accessToken, quiz.id());
        QuizAttemptResponse attempt = startAttempt(accessToken, quiz.id());
        assertThat(attempt.deadlineAt()).isNull();

        Question first = groundTruth.get(0);
        AnswerResponse correctAns = saveAnswer(accessToken, attempt.id(), takeableQuestions[0].id(),
                (int) first.getCorrectIndex());
        assertThat(correctAns.isCorrect()).isTrue();
        assertThat(correctAns.explanation()).isNotBlank();

        // Partial-success generation can legitimately yield just one question — only exercise the
        // wrong-answer path when there's a second question to use.
        if (groundTruth.size() >= 2) {
            Question second = groundTruth.get(1);
            int wrongIndex = (second.getCorrectIndex() + 1) % 4;
            AnswerResponse wrongAns = saveAnswer(accessToken, attempt.id(), takeableQuestions[1].id(), wrongIndex);
            assertThat(wrongAns.isCorrect()).isFalse();
            assertThat(wrongAns.explanation()).isNotBlank();
        }
    }

    @Test
    void answersAreRejectedOncePastAnExamDeadlineAndTheAttemptAutoExpires() throws InterruptedException {
        String accessToken = registerAndLogin();
        UUID ownerId = UUID.fromString(currentUserId(accessToken));
        UUID documentId = uploadAndIngest(accessToken);

        QuizResponse quiz = buildQuiz(accessToken, documentId, "EXAM", 10);
        List<Question> groundTruth = questionRepository
                .findByQuestionSetIdAndOwnerIdOrderBySortOrderAsc(quiz.questionSetId(), ownerId);
        QuizQuestionResponse[] takeableQuestions = questionsFor(accessToken, quiz.id());
        UUID attemptId = startAttempt(accessToken, quiz.id()).id();

        // One real answer saved while still in progress, so expiry scores from real progress.
        Question firstQuestion = groundTruth.get(0);
        saveAnswer(accessToken, attemptId, takeableQuestions[0].id(), (int) firstQuestion.getCorrectIndex());

        // Deterministic instead of a real Thread.sleep past the deadline — same technique as
        // FlashcardGenerationIntegrationTest's optimistic-lock test manipulating state directly.
        jdbcTemplate.update("UPDATE quiz_attempts SET deadline_at = now() - interval '1 hour' WHERE id = ?",
                attemptId);

        // Reuses question 0 (re-answering it) when generation only yielded one question —
        // partial-success generation can legitimately produce fewer than requestedCount.
        int lateAnswerIdx = Math.min(1, groundTruth.size() - 1);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> lateAnswer = restTemplate.exchange(
                "/api/v1/quiz-attempts/" + attemptId + "/answers/" + takeableQuestions[lateAnswerIdx].id(),
                HttpMethod.PUT, new HttpEntity<>(new AnswerRequest(0), headers), String.class);
        assertThat(lateAnswer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = objectMapper.readTree(lateAnswer.getBody());
        assertThat(problem.get("code").asString()).isEqualTo("QUIZ_ATTEMPT_EXPIRED");

        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth(accessToken);
        ResponseEntity<QuizAttemptResponse> attemptAfter = restTemplate.exchange(
                "/api/v1/quiz-attempts/" + attemptId, HttpMethod.GET, new HttpEntity<>(getHeaders),
                QuizAttemptResponse.class);
        assertThat(attemptAfter.getBody().status()).isEqualTo("EXPIRED");
        int n = groundTruth.size();
        assertThat(attemptAfter.getBody().unansweredCount()).isEqualTo((short) (n - 1));
        assertThat(
                attemptAfter.getBody().correctCount() + attemptAfter.getBody().incorrectCount()).isEqualTo((short) 1);

        // submit() never fails, even on an already-expired attempt — it just returns the result.
        QuizResultResponse resubmitted = submit(accessToken, attemptId);
        assertThat(resubmitted.attempt().status()).isEqualTo("EXPIRED");
        assertThat(resubmitted.attempt().score()).isEqualByComparingTo(attemptAfter.getBody().score());
    }

    @Test
    void requestingAnInvalidCountIsRejected() {
        String accessToken = registerAndLogin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", "quiz-bad-count-" + System.nanoTime());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/documents/" + UUID.randomUUID() + "/quizzes", HttpMethod.POST,
                new HttpEntity<>(new QuizRequest(com.studyflow.study.domain.QuizMode.EXAM, 7), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = objectMapper.readTree(response.getBody());
        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void requestingAQuizBeforeIngestionFinishesIsRejected() {
        String accessToken = registerAndLogin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "not-ready-quiz-" + System.nanoTime());

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

        HttpHeaders quizHeaders = new HttpHeaders();
        quizHeaders.setBearerAuth(accessToken);
        quizHeaders.setContentType(MediaType.APPLICATION_JSON);
        quizHeaders.add("Idempotency-Key", "quiz-not-ready-" + System.nanoTime());
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/documents/" + documentId + "/quizzes",
                HttpMethod.POST,
                new HttpEntity<>(new QuizRequest(com.studyflow.study.domain.QuizMode.EXAM, 10), quizHeaders),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = objectMapper.readTree(response.getBody());
        assertThat(problem.get("code").asString()).isEqualTo("DOCUMENT_NOT_READY");
    }

    private QuizResponse buildQuiz(String accessToken, UUID documentId, String mode, int requestedCount)
            throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", "quiz-" + mode + "-" + System.nanoTime());
        ResponseEntity<QuizJobResponse> response = restTemplate.exchange("/api/v1/documents/" + documentId + "/quizzes",
                HttpMethod.POST,
                new HttpEntity<>(new QuizRequest(com.studyflow.study.domain.QuizMode.valueOf(mode), requestedCount),
                        headers),
                QuizJobResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID jobId = response.getBody().jobId();

        UUID claimed = jobDispatcher.pollOnce();
        assertThat(claimed).isEqualTo(jobId);
        AiJob finishedJob = awaitJobTerminal(jobId);
        assertThat(finishedJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth(accessToken);
        ResponseEntity<QuizResponse[]> listResponse = restTemplate.exchange("/api/v1/documents/" + documentId
                + "/quizzes", HttpMethod.GET, new HttpEntity<>(getHeaders), QuizResponse[].class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotEmpty();
        QuizResponse quiz = listResponse.getBody()[0];
        assertThat(quiz.questionCount()).isGreaterThan(0).isLessThanOrEqualTo(requestedCount);
        return quiz;
    }

    private QuizQuestionResponse[] questionsFor(String accessToken, UUID quizId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<QuizQuestionResponse[]> response = restTemplate.exchange("/api/v1/quizzes/" + quizId
                + "/questions", HttpMethod.GET, new HttpEntity<>(headers), QuizQuestionResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private QuizAttemptResponse startAttempt(String accessToken, UUID quizId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<QuizAttemptResponse> response = restTemplate.exchange("/api/v1/quizzes/" + quizId
                + "/attempts", HttpMethod.POST, new HttpEntity<>(headers), QuizAttemptResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AnswerResponse saveAnswer(String accessToken, UUID attemptId, UUID questionId, Integer selectedIndex) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AnswerResponse> response = restTemplate.exchange(
                "/api/v1/quiz-attempts/" + attemptId + "/answers/" + questionId, HttpMethod.PUT,
                new HttpEntity<>(new AnswerRequest(selectedIndex), headers), AnswerResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private QuizResultResponse submit(String accessToken, UUID attemptId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<QuizResultResponse> response = restTemplate.exchange(
                "/api/v1/quiz-attempts/" + attemptId + "/submit", HttpMethod.POST, new HttpEntity<>(headers),
                QuizResultResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
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
        headers.add("Idempotency-Key", "ingest-for-quiz-" + System.nanoTime());

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
        // 1000 * 200ms = 200s, comfortably past specs/01-architecture.md's documented 20-180s
        // async-job budget (retries with backoff can push a real call toward that upper bound).
        for (int i = 0; i < 1000; i++) {
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
        String email = "quiz" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Quiz Test User", (short) 2000), Object.class);
        ResponseEntity<AccessTokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        return login.getBody().accessToken();
    }
}
