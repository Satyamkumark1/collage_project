package com.studyflow.study.domain;

import com.studyflow.common.id.UuidV7;
import com.studyflow.study.service.Sm2Calculator.Sm2Result;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * The first mutable row in {@code study/} — every other batch-study table (summaries, key_points,
 * question_sets/questions) is insert-only. SM-2 review state is genuinely mutated on each review,
 * so this entity carries {@code @Version} for JPA optimistic locking (see docs/DECISIONS.md) — a
 * double-tapped review on a flaky connection should fail loudly, not silently corrupt
 * spaced-repetition state.
 */
@Entity
@Table(name = "flashcards")
public class Flashcard {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "document_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "job_id", columnDefinition = "uuid", updatable = false)
    private UUID jobId;

    @Column(name = "front_md", nullable = false, updatable = false)
    private String frontMd;

    @Column(name = "back_md", nullable = false, updatable = false)
    private String backMd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String citationsJson;

    @Column(name = "ease_factor", nullable = false)
    private BigDecimal easeFactor = new BigDecimal("2.50");

    @Column(name = "interval_days", nullable = false)
    private int intervalDays = 0;

    @Column(name = "repetitions", nullable = false)
    private short repetitions = 0;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt = Instant.now();

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    @Column(name = "last_quality")
    private Short lastQuality;

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false, updatable = false)
    private int promptVersion;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Flashcard() {
        // JPA
    }

    public Flashcard(UUID documentId, UUID ownerId, UUID jobId, String frontMd, String backMd, String citationsJson,
            String model, int promptVersion) {
        this.documentId = documentId;
        this.ownerId = ownerId;
        this.jobId = jobId;
        this.frontMd = frontMd;
        this.backMd = backMd;
        this.citationsJson = citationsJson;
        this.model = model;
        this.promptVersion = promptVersion;
    }

    public void applyReview(Sm2Result result, short quality) {
        this.easeFactor = result.easeFactor();
        this.intervalDays = result.intervalDays();
        this.repetitions = (short) result.repetitions();
        this.dueAt = result.dueAt();
        this.lastReviewedAt = Instant.now();
        this.lastQuality = quality;
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

    public String getFrontMd() {
        return frontMd;
    }

    public String getBackMd() {
        return backMd;
    }

    public String getCitationsJson() {
        return citationsJson;
    }

    public BigDecimal getEaseFactor() {
        return easeFactor;
    }

    public int getIntervalDays() {
        return intervalDays;
    }

    public short getRepetitions() {
        return repetitions;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getLastReviewedAt() {
        return lastReviewedAt;
    }

    public Short getLastQuality() {
        return lastQuality;
    }

    public String getModel() {
        return model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
