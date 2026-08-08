package com.studyflow.study.dto;

import com.studyflow.study.domain.Summary;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record SummaryResponse(
        UUID id,
        UUID documentId,
        String summaryType,
        String contentMd,
        JsonNode citations,
        String model,
        Instant createdAt) {

    public static SummaryResponse from(Summary summary, ObjectMapper objectMapper) {
        JsonNode citations = objectMapper.readTree(summary.getCitationsJson());
        return new SummaryResponse(summary.getId(), summary.getDocumentId(), summary.getSummaryType().name(),
                summary.getContentMd(), citations, summary.getModel(), summary.getCreatedAt());
    }
}
