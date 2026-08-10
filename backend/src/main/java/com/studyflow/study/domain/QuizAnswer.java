package com.studyflow.study.domain;

import com.studyflow.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per (attempt, question), upserted from the service layer as the student answers or
 * changes an answer — see docs/DECISIONS.md. {@code selectedIndex} is nullable so an answer can
 * be explicitly cleared.
 */
@Entity
@Table(name = "quiz_answers")
public class QuizAnswer {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "attempt_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "question_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "selected_index")
    private Short selectedIndex;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt = Instant.now();

    protected QuizAnswer() {
        // JPA
    }

    public QuizAnswer(UUID attemptId, UUID questionId, UUID ownerId, Short selectedIndex) {
        this.attemptId = attemptId;
        this.questionId = questionId;
        this.ownerId = ownerId;
        this.selectedIndex = selectedIndex;
    }

    public void applyAnswer(Short selectedIndex) {
        this.selectedIndex = selectedIndex;
        this.answeredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public UUID getQuestionId() {
        return questionId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public Short getSelectedIndex() {
        return selectedIndex;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }
}
