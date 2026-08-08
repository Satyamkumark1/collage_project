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

/** Regeneration creates a new row — never overwrites (see specs/10-study-features.md). */
@Entity
@Table(name = "summaries")
public class Summary {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "document_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_type", columnDefinition = "varchar(20)", nullable = false, updatable = false)
    private SummaryType summaryType = SummaryType.QUICK;

    @Column(name = "content_md", nullable = false, updatable = false)
    private String contentMd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String citationsJson;

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false, updatable = false)
    private int promptVersion;

    @Column(name = "job_id", columnDefinition = "uuid", updatable = false)
    private UUID jobId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Summary() {
        // JPA
    }

    public Summary(UUID documentId, UUID ownerId, String contentMd, String citationsJson, String model,
            int promptVersion, UUID jobId) {
        this.documentId = documentId;
        this.ownerId = ownerId;
        this.contentMd = contentMd;
        this.citationsJson = citationsJson;
        this.model = model;
        this.promptVersion = promptVersion;
        this.jobId = jobId;
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

    public SummaryType getSummaryType() {
        return summaryType;
    }

    public String getContentMd() {
        return contentMd;
    }

    public String getCitationsJson() {
        return citationsJson;
    }

    public String getModel() {
        return model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
