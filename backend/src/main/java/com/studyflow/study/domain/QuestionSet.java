package com.studyflow.study.domain;

import com.studyflow.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One row per batch request; regeneration creates a new row (same posture as {@link Summary}). */
@Entity
@Table(name = "question_sets")
public class QuestionSet {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "document_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "job_id", columnDefinition = "uuid", updatable = false)
    private UUID jobId;

    @Column(name = "requested_count", nullable = false, updatable = false)
    private short requestedCount;

    @Column(name = "generated_count", nullable = false, updatable = false)
    private short generatedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "difficulty_mix", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String difficultyMixJson;

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false, updatable = false)
    private int promptVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected QuestionSet() {
        // JPA
    }

    public QuestionSet(UUID documentId, UUID ownerId, UUID jobId, short requestedCount, short generatedCount,
            String difficultyMixJson, String model, int promptVersion) {
        this.documentId = documentId;
        this.ownerId = ownerId;
        this.jobId = jobId;
        this.requestedCount = requestedCount;
        this.generatedCount = generatedCount;
        this.difficultyMixJson = difficultyMixJson;
        this.model = model;
        this.promptVersion = promptVersion;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public short getRequestedCount() {
        return requestedCount;
    }

    public short getGeneratedCount() {
        return generatedCount;
    }

    public String getDifficultyMixJson() {
        return difficultyMixJson;
    }

    public String getModel() {
        return model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
