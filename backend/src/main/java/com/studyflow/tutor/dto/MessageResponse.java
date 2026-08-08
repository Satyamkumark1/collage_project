package com.studyflow.tutor.dto;

import com.studyflow.tutor.domain.Message;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record MessageResponse(
        UUID id,
        String role,
        String content,
        JsonNode citations,
        Boolean grounded,
        Boolean beyondNotes,
        Instant createdAt) {

    public static MessageResponse from(Message message, ObjectMapper objectMapper) {
        JsonNode citations = message.getCitationsJson() == null ? null
                : objectMapper.readTree(message.getCitationsJson());
        return new MessageResponse(message.getId(), message.getRole().name(), message.getContent(), citations,
                message.getGrounded(), message.getBeyondNotes(), message.getCreatedAt());
    }
}
