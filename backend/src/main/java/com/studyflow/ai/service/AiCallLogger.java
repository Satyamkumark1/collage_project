package com.studyflow.ai.service;

import com.studyflow.ai.domain.AiCall;
import com.studyflow.ai.domain.AiOutcome;
import com.studyflow.ai.repo.AiCallRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Published entry point for other features to write to the {@code ai_calls} cost ledger, so
 * callers (e.g. {@code study}) never inject {@link AiCallRepository} directly — see
 * specs/01-architecture.md cross-feature access rule.
 */
@Service
public class AiCallLogger {

    private final AiCallRepository repository;

    public AiCallLogger(AiCallRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(UUID ownerId, UUID jobId, String provider, String model, String purpose, int promptVersion,
            Integer tokensIn, Integer tokensOut, Integer latencyMs, String finishReason, AiOutcome outcome,
            int attemptNo) {
        repository.save(new AiCall(ownerId, jobId, provider, model, purpose, promptVersion, tokensIn, tokensOut,
                latencyMs, finishReason, outcome, attemptNo));
    }
}
