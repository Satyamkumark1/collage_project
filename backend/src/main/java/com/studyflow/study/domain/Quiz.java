package com.studyflow.study.domain;

import com.studyflow.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A thin wrapper around a fresh MCQ batch: {@code question_set_id} points at the
 * {@link QuestionSet} generated for this quiz (via the existing MCQ pipeline, unchanged), plus
 * timing/scoring config derived from {@link QuizMode} at build time. Insert-only, same posture as
 * {@link QuestionSet} — retaking with different settings builds a new quiz. See
 * docs/DECISIONS.md.
 */
@Entity
@Table(name = "quizzes")
public class Quiz {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "document_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "question_set_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID questionSetId;

    @Column(name = "job_id", columnDefinition = "uuid", updatable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", columnDefinition = "varchar(10)", nullable = false, updatable = false)
    private QuizMode mode;

    @Column(name = "question_count", nullable = false, updatable = false)
    private short questionCount;

    @Column(name = "time_limit_seconds", updatable = false)
    private Integer timeLimitSeconds;

    @Column(name = "negative_marking_fraction", nullable = false, updatable = false)
    private BigDecimal negativeMarkingFraction;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Quiz() {
        // JPA
    }

    public Quiz(UUID documentId, UUID ownerId, UUID questionSetId, UUID jobId, QuizMode mode, short questionCount,
            Integer timeLimitSeconds, BigDecimal negativeMarkingFraction) {
        this.documentId = documentId;
        this.ownerId = ownerId;
        this.questionSetId = questionSetId;
        this.jobId = jobId;
        this.mode = mode;
        this.questionCount = questionCount;
        this.timeLimitSeconds = timeLimitSeconds;
        this.negativeMarkingFraction = negativeMarkingFraction;
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

    public UUID getQuestionSetId() {
        return questionSetId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public QuizMode getMode() {
        return mode;
    }

    public short getQuestionCount() {
        return questionCount;
    }

    public Integer getTimeLimitSeconds() {
        return timeLimitSeconds;
    }

    public BigDecimal getNegativeMarkingFraction() {
        return negativeMarkingFraction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
