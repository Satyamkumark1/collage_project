package com.studyflow.study.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.study.domain.Question;
import com.studyflow.study.domain.Quiz;
import com.studyflow.study.domain.QuizAnswer;
import com.studyflow.study.domain.QuizAttempt;
import com.studyflow.study.domain.QuizAttemptStatus;
import com.studyflow.study.domain.QuizMode;
import com.studyflow.study.repo.QuestionRepository;
import com.studyflow.study.repo.QuizAnswerRepository;
import com.studyflow.study.repo.QuizAttemptRepository;
import com.studyflow.study.repo.QuizRepository;
import com.studyflow.study.service.QuizScorer.ScoreBreakdown;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the quiz-attempt lifecycle: start → incremental answer saves → submit (or server-detected
 * expiry) → result. Server-authoritative timing means every mutating call re-checks {@code now()}
 * against the attempt's own {@code deadlineAt}, never a client-supplied timestamp — see
 * docs/DECISIONS.md. Pure synchronous CRUD (no LLM calls), same cost class as
 * {@link FlashcardPersistenceService#review}.
 */
@Service
public class QuizAttemptService {

    private final QuizRepository quizRepository;
    private final QuizAttemptRepository attemptRepository;
    private final QuizAnswerRepository answerRepository;
    private final QuestionRepository questionRepository;

    public QuizAttemptService(QuizRepository quizRepository, QuizAttemptRepository attemptRepository,
            QuizAnswerRepository answerRepository, QuestionRepository questionRepository) {
        this.quizRepository = quizRepository;
        this.attemptRepository = attemptRepository;
        this.answerRepository = answerRepository;
        this.questionRepository = questionRepository;
    }

    public record AnswerOutcome(QuizAnswer answer, Boolean isCorrect, String explanation) {
    }

    /** {@code selectedIndexByQuestionId} omits unanswered questions. */
    public record ResultView(QuizAttempt attempt, List<Question> questions, Map<UUID, Short> selectedIndexByQuestionId) {
    }

    @Transactional
    public QuizAttempt start(UUID quizId, UUID ownerId) {
        Quiz quiz = requireQuiz(quizId, ownerId);
        return attemptRepository.save(new QuizAttempt(quiz.getId(), ownerId, quiz.getTimeLimitSeconds()));
    }

    @Transactional
    public QuizAttempt get(UUID attemptId, UUID ownerId) {
        QuizAttempt attempt = requireAttempt(attemptId, ownerId);
        Quiz quiz = requireQuiz(attempt.getQuizId(), ownerId);
        return lazyExpireIfNeeded(attempt, quiz);
    }

    /**
     * REVISION mode returns the question's correctness/explanation inline (a formative pass, not
     * an assessment); EXAM/PRACTICE withhold it until submit — see docs/DECISIONS.md.
     */
    @Transactional
    public AnswerOutcome saveAnswer(UUID attemptId, UUID questionId, UUID ownerId, Short selectedIndex) {
        QuizAttempt attempt = requireAttempt(attemptId, ownerId);
        Quiz quiz = requireQuiz(attempt.getQuizId(), ownerId);

        if (attempt.getStatus() != QuizAttemptStatus.IN_PROGRESS) {
            throw new ApiException(ErrorCode.QUIZ_ATTEMPT_NOT_IN_PROGRESS, "This attempt is no longer in progress");
        }
        // Only EXAM hard-enforces the deadline — PRACTICE shows the same countdown but keeps
        // accepting writes past it (an "overtime" indicator, not a hard stop); REVISION is
        // untimed. See docs/DECISIONS.md.
        //
        // Deliberately does NOT call finalizeAttempt here: this method is @Transactional with no
        // noRollbackFor, and ApiException is an unchecked RuntimeException, so Spring rolls back
        // the whole transaction — including a finalizeAttempt write — the instant this throws.
        // Finalization happens lazily instead, the next time get()/result()/submit() reads this
        // attempt (each independently re-checks the deadline in its own transaction).
        if (quiz.getMode() == QuizMode.EXAM && attempt.isPastDeadline(Instant.now())) {
            throw new ApiException(ErrorCode.QUIZ_ATTEMPT_EXPIRED,
                    "Time's up — this attempt will be scored from whatever was already saved");
        }

        Question question = questionRepository.findByIdAndOwnerId(questionId, ownerId)
                .filter(q -> q.getQuestionSetId().equals(quiz.getQuestionSetId()))
                .orElseThrow(
                        () -> new ApiException(ErrorCode.VALIDATION_FAILED, "questionId does not belong to this quiz"));

        QuizAnswer answer = answerRepository.findByAttemptIdAndQuestionIdAndOwnerId(attemptId, questionId, ownerId)
                .orElseGet(() -> new QuizAnswer(attemptId, questionId, ownerId, null));
        answer.applyAnswer(selectedIndex);
        answer = answerRepository.save(answer);

        if (quiz.getMode() == QuizMode.REVISION && selectedIndex != null) {
            boolean isCorrect = selectedIndex == question.getCorrectIndex();
            return new AnswerOutcome(answer, isCorrect, question.getExplanation());
        }
        return new AnswerOutcome(answer, null, null);
    }

    /**
     * Always accepted, even past an EXAM deadline — only mid-attempt answer writes hard-reject on
     * expiry, submit itself never fails. Idempotent: resubmitting an already-terminal attempt just
     * returns its stored result. See docs/DECISIONS.md.
     */
    @Transactional
    public ResultView submit(UUID attemptId, UUID ownerId) {
        QuizAttempt attempt = requireAttempt(attemptId, ownerId);
        Quiz quiz = requireQuiz(attempt.getQuizId(), ownerId);
        if (attempt.getStatus() == QuizAttemptStatus.IN_PROGRESS) {
            QuizAttemptStatus terminalStatus = quiz.getMode() == QuizMode.EXAM && attempt.isPastDeadline(Instant.now())
                    ? QuizAttemptStatus.EXPIRED
                    : QuizAttemptStatus.SUBMITTED;
            finalizeAttempt(attempt, quiz, terminalStatus);
        }
        return buildResultView(attempt, quiz);
    }

    /**
     * Answer key is only ever returned once the attempt is terminal — see docs/DECISIONS.md. Uses
     * {@code QUIZ_ATTEMPT_NOT_SUBMITTED}, distinct from the {@code QUIZ_ATTEMPT_NOT_IN_PROGRESS}
     * {@link #saveAnswer} throws on a terminal attempt — same 409 status, but the opposite
     * attempt-state condition, so the frontend needs a stable code to tell them apart.
     */
    @Transactional
    public ResultView result(UUID attemptId, UUID ownerId) {
        QuizAttempt attempt = requireAttempt(attemptId, ownerId);
        Quiz quiz = requireQuiz(attempt.getQuizId(), ownerId);
        attempt = lazyExpireIfNeeded(attempt, quiz);
        if (attempt.getStatus() == QuizAttemptStatus.IN_PROGRESS) {
            throw new ApiException(ErrorCode.QUIZ_ATTEMPT_NOT_SUBMITTED,
                    "Submit the attempt before viewing its result");
        }
        return buildResultView(attempt, quiz);
    }

    @Transactional
    public List<QuizAttempt> listForQuiz(UUID quizId, UUID ownerId) {
        requireQuiz(quizId, ownerId);
        return attemptRepository.findByQuizIdAndOwnerIdOrderByStartedAtDesc(quizId, ownerId);
    }

    /**
     * The student's own previously saved picks, for resuming an in-progress attempt after a page
     * reload — not the answer key, so this is safe to expose regardless of attempt status.
     */
    @Transactional
    public List<QuizAnswer> listAnswers(UUID attemptId, UUID ownerId) {
        requireAttempt(attemptId, ownerId);
        return answerRepository.findByAttemptIdAndOwnerId(attemptId, ownerId);
    }

    private QuizAttempt lazyExpireIfNeeded(QuizAttempt attempt, Quiz quiz) {
        if (attempt.getStatus() == QuizAttemptStatus.IN_PROGRESS && quiz.getMode() == QuizMode.EXAM
                && attempt.isPastDeadline(Instant.now())) {
            finalizeAttempt(attempt, quiz, QuizAttemptStatus.EXPIRED);
        }
        return attempt;
    }

    private void finalizeAttempt(QuizAttempt attempt, Quiz quiz, QuizAttemptStatus terminalStatus) {
        List<Question> questions = questionRepository
                .findByQuestionSetIdAndOwnerIdOrderBySortOrderAsc(quiz.getQuestionSetId(), attempt.getOwnerId());
        Map<UUID, Integer> selectedByQuestionId = answerRepository
                .findByAttemptIdAndOwnerId(attempt.getId(), attempt.getOwnerId()).stream()
                .filter(a -> a.getSelectedIndex() != null)
                .collect(Collectors.toMap(QuizAnswer::getQuestionId, a -> (int) a.getSelectedIndex()));
        ScoreBreakdown breakdown = QuizScorer.score(questions, selectedByQuestionId, quiz.getNegativeMarkingFraction());
        attempt.finalizeScore(terminalStatus, breakdown);
        attemptRepository.save(attempt);
    }

    private ResultView buildResultView(QuizAttempt attempt, Quiz quiz) {
        List<Question> questions = questionRepository
                .findByQuestionSetIdAndOwnerIdOrderBySortOrderAsc(quiz.getQuestionSetId(), attempt.getOwnerId());
        Map<UUID, Short> selected = answerRepository.findByAttemptIdAndOwnerId(attempt.getId(), attempt.getOwnerId())
                .stream()
                .filter(a -> a.getSelectedIndex() != null)
                .collect(Collectors.toMap(QuizAnswer::getQuestionId, QuizAnswer::getSelectedIndex));
        return new ResultView(attempt, questions, selected);
    }

    private Quiz requireQuiz(UUID quizId, UUID ownerId) {
        return quizRepository.findByIdAndOwnerId(quizId, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.QUIZ_NOT_FOUND, "No quiz with that id"));
    }

    private QuizAttempt requireAttempt(UUID attemptId, UUID ownerId) {
        return attemptRepository.findByIdAndOwnerId(attemptId, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.QUIZ_ATTEMPT_NOT_FOUND, "No quiz attempt with that id"));
    }
}
