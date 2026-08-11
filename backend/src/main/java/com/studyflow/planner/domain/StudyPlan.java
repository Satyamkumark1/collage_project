package com.studyflow.planner.domain;

import com.studyflow.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** Insert-only, same posture as {@code Quiz} — a new exam date makes a new plan. */
@Entity
@Table(name = "study_plans")
public class StudyPlan {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "document_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "exam_date", nullable = false, updatable = false)
    private LocalDate examDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StudyPlan() {
        // JPA
    }

    public StudyPlan(UUID documentId, UUID ownerId, LocalDate examDate) {
        this.documentId = documentId;
        this.ownerId = ownerId;
        this.examDate = examDate;
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

    public LocalDate getExamDate() {
        return examDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
