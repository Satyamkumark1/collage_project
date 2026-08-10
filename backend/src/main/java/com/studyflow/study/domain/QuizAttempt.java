package com.studyflow.study.domain;

import com.studyflow.common.id.UuidV7;
import com.studyflow.study.service.QuizScorer.ScoreBreakdown;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * The second mutable row in {@code study/} (after {@link Flashcard}) — status/score genuinely
 * change on submit or lazy server-side expiry, so this carries {@code @Version} for the same
 * reason: a concurrent submit-vs-expire race (two tabs on the same attempt) must fail loudly, not
 * silently double-score. See docs/DECISIONS.md.
 */
@Entity
@Table(name = "quiz_attempts")
public class QuizAttempt {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "quiz_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID quizId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "varchar(12)", nullable = false)
    private QuizAttemptStatus status = QuizAttemptStatus.IN_PROGRESS;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "deadline_at", updatable = false)
    private Instant deadlineAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "score")
    private BigDecimal score;

    @Column(name = "max_score")
    private Short maxScore;

    @Column(name = "correct_count")
    private Short correctCount;

    @Column(name = "incorrect_count")
    private Short incorrectCount;

    @Column(name = "unanswered_count")
    private Short unansweredCount;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected QuizAttempt() {
        // JPA
    }

    public QuizAttempt(UUID quizId, UUID ownerId, Integer timeLimitSeconds) {
        this.quizId = quizId;
        this.ownerId = ownerId;
        this.startedAt = Instant.now();
        this.deadlineAt = timeLimitSeconds == null ? null : startedAt.plusSeconds(timeLimitSeconds);
    }

    /** True once {@link #deadlineAt} has passed — callers decide what that means per mode. */
    public boolean isPastDeadline(Instant now) {
        return deadlineAt != null && now.isAfter(deadlineAt);
    }

    public void finalizeScore(QuizAttemptStatus terminalStatus, ScoreBreakdown breakdown) {
        this.status = terminalStatus;
        this.score = breakdown.score();
        this.maxScore = breakdown.maxScore();
        this.correctCount = breakdown.correctCount();
        this.incorrectCount = breakdown.incorrectCount();
        this.unansweredCount = breakdown.unansweredCount();
        this.submittedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getQuizId() {
        return quizId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public QuizAttemptStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public BigDecimal getScore() {
        return score;
    }

    public Short getMaxScore() {
        return maxScore;
    }

    public Short getCorrectCount() {
        return correctCount;
    }

    public Short getIncorrectCount() {
        return incorrectCount;
    }

    public Short getUnansweredCount() {
        return unansweredCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
