package com.studyflow.study.domain;

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

/** Regeneration creates a new job_id batch — never overwrites (same posture as {@link Summary}). */
@Entity
@Table(name = "key_points")
public class KeyPoint {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "document_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "job_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", columnDefinition = "varchar(20)", nullable = false, updatable = false)
    private KeyPointCategory category;

    @Column(name = "label", nullable = false, updatable = false)
    private String label;

    @Column(name = "content_md", nullable = false, updatable = false)
    private String contentMd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String citationsJson;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private short sortOrder;

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false, updatable = false)
    private int promptVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KeyPoint() {
        // JPA
    }

    public KeyPoint(UUID documentId, UUID ownerId, UUID jobId, KeyPointCategory category, String label,
            String contentMd, String citationsJson, short sortOrder, String model, int promptVersion) {
        this.documentId = documentId;
        this.ownerId = ownerId;
        this.jobId = jobId;
        this.category = category;
        this.label = label;
        this.contentMd = contentMd;
        this.citationsJson = citationsJson;
        this.sortOrder = sortOrder;
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

    public KeyPointCategory getCategory() {
        return category;
    }

    public String getLabel() {
        return label;
    }

    public String getContentMd() {
        return contentMd;
    }

    public String getCitationsJson() {
        return citationsJson;
    }

    public short getSortOrder() {
        return sortOrder;
    }

    public String getModel() {
        return model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
