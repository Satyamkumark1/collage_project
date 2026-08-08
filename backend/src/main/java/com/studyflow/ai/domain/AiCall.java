package com.studyflow.ai.domain;

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

/** The cost ledger, abuse detector, and quality dashboard — written on every AI call. */
@Entity
@Table(name = "ai_calls")
public class AiCall {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "job_id", columnDefinition = "uuid", updatable = false)
    private UUID jobId;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    @Column(name = "purpose", nullable = false, updatable = false)
    private String purpose;

    @Column(name = "prompt_version", nullable = false, updatable = false)
    private int promptVersion;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "cost_micro_inr")
    private Long costMicroInr;

    @Column(name = "finish_reason")
    private String finishReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", columnDefinition = "varchar(20)", nullable = false, updatable = false)
    private AiOutcome outcome;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiCall() {
        // JPA
    }

    public AiCall(UUID ownerId, UUID jobId, String provider, String model, String purpose, int promptVersion,
            Integer tokensIn, Integer tokensOut, Integer latencyMs, String finishReason, AiOutcome outcome,
            int attemptNo) {
        this.ownerId = ownerId;
        this.jobId = jobId;
        this.provider = provider;
        this.model = model;
        this.purpose = purpose;
        this.promptVersion = promptVersion;
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        this.latencyMs = latencyMs;
        this.finishReason = finishReason;
        this.outcome = outcome;
        this.attemptNo = attemptNo;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public AiOutcome getOutcome() {
        return outcome;
    }
}
