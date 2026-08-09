package com.studyflow.study.dto;

import com.studyflow.study.domain.Flashcard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record FlashcardResponse(
        UUID id,
        UUID documentId,
        String frontMd,
        String backMd,
        JsonNode citations,
        BigDecimal easeFactor,
        int intervalDays,
        int repetitions,
        Instant dueAt,
        Instant lastReviewedAt,
        Short lastQuality,
        String model,
        Instant createdAt) {

    public static FlashcardResponse from(Flashcard flashcard, ObjectMapper objectMapper) {
        JsonNode citations = objectMapper.readTree(flashcard.getCitationsJson());
        return new FlashcardResponse(flashcard.getId(), flashcard.getDocumentId(), flashcard.getFrontMd(),
                flashcard.getBackMd(), citations, flashcard.getEaseFactor(), flashcard.getIntervalDays(),
                flashcard.getRepetitions(), flashcard.getDueAt(), flashcard.getLastReviewedAt(),
                flashcard.getLastQuality(), flashcard.getModel(), flashcard.getCreatedAt());
    }
}
