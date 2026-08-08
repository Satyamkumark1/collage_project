package com.studyflow.tutor.dto;

import com.studyflow.tutor.domain.Conversation;
import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(UUID id, UUID documentId, Instant createdAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(conversation.getId(), conversation.getDocumentId(),
                conversation.getCreatedAt());
    }
}
