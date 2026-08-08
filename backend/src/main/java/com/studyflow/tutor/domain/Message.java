package com.studyflow.tutor.domain;

import com.studyflow.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A user turn or an assistant turn in a {@link Conversation}. Assistant-only columns
 * ({@code citations}, {@code grounded}, {@code beyondNotes}, {@code model}, {@code promptVersion})
 * are null on user messages — see specs/09-rag.md §Grounding contract for the tutor.
 */
@Entity
@Table(name = "messages")
public class Message {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "conversation_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", columnDefinition = "varchar(10)", nullable = false, updatable = false)
    private MessageRole role;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "jsonb", updatable = false)
    private String citationsJson;

    @Column(name = "grounded", updatable = false)
    private Boolean grounded;

    @Column(name = "beyond_notes", updatable = false)
    private Boolean beyondNotes;

    @Column(name = "model", updatable = false)
    private String model;

    @Column(name = "prompt_version", updatable = false)
    private Integer promptVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Message() {
        // JPA
    }

    private Message(UUID conversationId, UUID ownerId, MessageRole role, String content, String citationsJson,
            Boolean grounded, Boolean beyondNotes, String model, Integer promptVersion) {
        this.conversationId = conversationId;
        this.ownerId = ownerId;
        this.role = role;
        this.content = content;
        this.citationsJson = citationsJson;
        this.grounded = grounded;
        this.beyondNotes = beyondNotes;
        this.model = model;
        this.promptVersion = promptVersion;
    }

    public static Message userTurn(UUID conversationId, UUID ownerId, String content) {
        return new Message(conversationId, ownerId, MessageRole.USER, content, null, null, null, null, null);
    }

    public static Message assistantTurn(UUID conversationId, UUID ownerId, String content, String citationsJson,
            boolean grounded, boolean beyondNotes, String model, int promptVersion) {
        return new Message(conversationId, ownerId, MessageRole.ASSISTANT, content, citationsJson, grounded,
                beyondNotes, model, promptVersion);
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getCitationsJson() {
        return citationsJson;
    }

    public Boolean getGrounded() {
        return grounded;
    }

    public Boolean getBeyondNotes() {
        return beyondNotes;
    }

    public String getModel() {
        return model;
    }

    public Integer getPromptVersion() {
        return promptVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
