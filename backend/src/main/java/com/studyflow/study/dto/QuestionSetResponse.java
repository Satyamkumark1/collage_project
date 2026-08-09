package com.studyflow.study.dto;

import com.studyflow.study.domain.QuestionSet;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record QuestionSetResponse(
        UUID id,
        UUID documentId,
        int requestedCount,
        int generatedCount,
        JsonNode difficultyMix,
        String model,
        Instant createdAt) {

    public static QuestionSetResponse from(QuestionSet questionSet, ObjectMapper objectMapper) {
        JsonNode difficultyMix = objectMapper.readTree(questionSet.getDifficultyMixJson());
        return new QuestionSetResponse(questionSet.getId(), questionSet.getDocumentId(),
                questionSet.getRequestedCount(), questionSet.getGeneratedCount(), difficultyMix,
                questionSet.getModel(), questionSet.getCreatedAt());
    }
}
