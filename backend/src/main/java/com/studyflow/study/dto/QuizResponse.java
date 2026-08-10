package com.studyflow.study.dto;

import com.studyflow.study.domain.Quiz;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuizResponse(
        UUID id,
        UUID documentId,
        UUID questionSetId,
        String mode,
        int questionCount,
        Integer timeLimitSeconds,
        BigDecimal negativeMarkingFraction,
        Instant createdAt) {

    public static QuizResponse from(Quiz quiz) {
        return new QuizResponse(quiz.getId(), quiz.getDocumentId(), quiz.getQuestionSetId(), quiz.getMode().name(),
                quiz.getQuestionCount(), quiz.getTimeLimitSeconds(), quiz.getNegativeMarkingFraction(),
                quiz.getCreatedAt());
    }
}
