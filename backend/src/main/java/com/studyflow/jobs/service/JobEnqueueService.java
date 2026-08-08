package com.studyflow.jobs.service;

import com.studyflow.common.error.ErrorCode;
import com.studyflow.common.hash.Sha256;
import com.studyflow.common.quota.QuotaService;
import com.studyflow.common.quota.UsageMetric;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.repo.AiJobRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enqueue-time idempotency-key lookup, then input_fingerprint dedupe against SUCCEEDED jobs, then
 * the ai_jobs quota check — all inside one transaction, before any job is created (see
 * specs/07-jobs-and-async.md, specs/12-billing-and-quotas.md).
 */
@Service
public class JobEnqueueService {

    private final AiJobRepository repository;
    private final QuotaService quotaService;
    private final long aiJobsPerMonth;

    public JobEnqueueService(AiJobRepository repository, QuotaService quotaService,
            @Value("${studyflow.quota.ai-jobs-per-month}") long aiJobsPerMonth) {
        this.repository = repository;
        this.quotaService = quotaService;
        this.aiJobsPerMonth = aiJobsPerMonth;
    }

    @Transactional
    public AiJob enqueue(UUID ownerId, TaskType taskType, String paramsJson, String idempotencyKey,
            String fingerprint) {
        if (idempotencyKey != null) {
            Optional<AiJob> existing = repository.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        if (fingerprint != null) {
            Optional<AiJob> succeeded = repository
                    .findFirstByOwnerIdAndInputFingerprintAndStatusOrderByCreatedAtDesc(ownerId, fingerprint,
                            JobStatus.SUCCEEDED);
            if (succeeded.isPresent()) {
                return succeeded.get();
            }
        }
        quotaService.incrementAndEnforce(ownerId, UsageMetric.AI_JOBS, aiJobsPerMonth, ErrorCode.QUOTA_AI_EXCEEDED);
        AiJob job = new AiJob(ownerId, taskType, paramsJson, fingerprint, idempotencyKey);
        return repository.save(job);
    }

    public static String fingerprint(String... parts) {
        return Sha256.hex(String.join("|", parts));
    }
}
