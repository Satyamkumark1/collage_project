package com.studyflow.study.dto;

import com.studyflow.study.domain.KeyPoint;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record KeyPointResponse(
        UUID id,
        UUID documentId,
        String category,
        String label,
        String contentMd,
        JsonNode citations,
        String model,
        Instant createdAt) {

    public static KeyPointResponse from(KeyPoint keyPoint, ObjectMapper objectMapper) {
        JsonNode citations = objectMapper.readTree(keyPoint.getCitationsJson());
        return new KeyPointResponse(keyPoint.getId(), keyPoint.getDocumentId(), keyPoint.getCategory().name(),
                keyPoint.getLabel(), keyPoint.getContentMd(), citations, keyPoint.getModel(),
                keyPoint.getCreatedAt());
    }
}
