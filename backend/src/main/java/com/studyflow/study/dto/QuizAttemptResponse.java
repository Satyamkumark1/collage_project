package com.studyflow.study.dto;

import com.studyflow.study.domain.QuizAttempt;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuizAttemptResponse(
        UUID id,
        UUID quizId,
        String status,
        Instant startedAt,
        Instant deadlineAt,
        Instant submittedAt,
        BigDecimal score,
        Short maxScore,
        Short correctCount,
        Short incorrectCount,
        Short unansweredCount) {

    public static QuizAttemptResponse from(QuizAttempt attempt) {
        return new QuizAttemptResponse(attempt.getId(), attempt.getQuizId(), attempt.getStatus().name(),
                attempt.getStartedAt(), attempt.getDeadlineAt(), attempt.getSubmittedAt(), attempt.getScore(),
                attempt.getMaxScore(), attempt.getCorrectCount(), attempt.getIncorrectCount(),
                attempt.getUnansweredCount());
    }
}
