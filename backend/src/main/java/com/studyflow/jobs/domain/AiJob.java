package com.studyflow.jobs.domain;

import com.studyflow.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_jobs")
public class AiJob {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", columnDefinition = "varchar(40)", nullable = false, updatable = false)
    private TaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private JobStatus status = JobStatus.QUEUED;

    @Column(name = "priority", nullable = false)
    private int priority = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb", nullable = false)
    private String paramsJson = "{}";

    @Column(name = "input_fingerprint")
    private String inputFingerprint;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_ref", columnDefinition = "jsonb")
    private String resultRefJson;

    @Column(name = "progress_pct", nullable = false)
    private short progressPct = 0;

    @Column(name = "progress_stage")
    private String progressStage;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "run_after", nullable = false)
    private Instant runAfter = Instant.now();

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiJob() {
        // JPA
    }

    public AiJob(UUID ownerId, TaskType taskType, String paramsJson, String inputFingerprint,
            String idempotencyKey) {
        this.ownerId = ownerId;
        this.taskType = taskType;
        this.paramsJson = paramsJson;
        this.inputFingerprint = inputFingerprint;
        this.idempotencyKey = idempotencyKey;
    }

    public void markRunningStart() {
        this.status = JobStatus.RUNNING;
    }

    public void reportProgress(short pct, String stage) {
        this.progressPct = pct;
        this.progressStage = stage;
        this.heartbeatAt = Instant.now();
    }

    public void markSucceeded(String resultRefJson) {
        this.status = JobStatus.SUCCEEDED;
        this.resultRefJson = resultRefJson;
        this.progressPct = 100;
        this.finishedAt = Instant.now();
    }

    public void markFailed(String errorCode, String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.finishedAt = Instant.now();
    }

    public void requeueAfterStaleHeartbeat(Instant nextRunAfter) {
        this.status = JobStatus.QUEUED;
        this.runAfter = nextRunAfter;
    }

    public boolean canRetry() {
        return attempts < maxAttempts;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public JobStatus getStatus() {
        return status;
    }

    public String getParamsJson() {
        return paramsJson;
    }

    public String getInputFingerprint() {
        return inputFingerprint;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getResultRefJson() {
        return resultRefJson;
    }

    public short getProgressPct() {
        return progressPct;
    }

    public String getProgressStage() {
        return progressStage;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
