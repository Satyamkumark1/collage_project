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

@Entity
@Table(name = "study_sessions")
public class StudySession {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "plan_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "document_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "scheduled_date", nullable = false, updatable = false)
    private LocalDate scheduledDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StudySession() {
        // JPA
    }

    public StudySession(UUID planId, UUID documentId, UUID ownerId, LocalDate scheduledDate) {
        this.planId = planId;
        this.documentId = documentId;
        this.ownerId = ownerId;
        this.scheduledDate = scheduledDate;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public LocalDate getScheduledDate() {
        return scheduledDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
