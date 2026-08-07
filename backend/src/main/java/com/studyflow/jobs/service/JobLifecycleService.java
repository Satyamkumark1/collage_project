package com.studyflow.jobs.service;

import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.repo.AiJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * All {@link AiJob} state transitions after claim, each its own short transaction — a running
 * handler can take minutes, so no single transaction spans the whole job (see
 * specs/07-jobs-and-async.md).
 */
@Service
public class JobLifecycleService {

    private static final long[] BACKOFF_SECONDS = {5, 20, 80};
    private static final long STALE_HEARTBEAT_SECONDS = 90;

    private final AiJobRepository repository;

    public JobLifecycleService(AiJobRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(UUID jobId, int pct, String stage) {
        repository.findById(jobId).ifPresent(job -> job.reportProgress((short) pct, stage));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchHeartbeat(UUID jobId) {
        repository.findById(jobId)
                .ifPresent(job -> job.reportProgress(job.getProgressPct(), job.getProgressStage()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(UUID jobId, String resultRefJson) {
        repository.findById(jobId).ifPresent(job -> job.markSucceeded(resultRefJson));
    }

    /** Non-transient failure (validation, quota exhaustion, unexpected error): straight to FAILED. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, String errorCode, String errorMessage) {
        repository.findById(jobId).ifPresent(job -> job.markFailed(errorCode, errorMessage));
    }

    /** Transient failure: retry with backoff if attempts remain, else FAILED. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryOrFail(UUID jobId, String errorCode, String errorMessage) {
        repository.findById(jobId).ifPresent(job -> {
            if (job.canRetry()) {
                job.requeueAfterStaleHeartbeat(nextRunAfter(job.getAttempts()));
            } else {
                job.markFailed(errorCode, errorMessage);
            }
        });
    }

    /** JobSweeper: requeues RUNNING jobs whose heartbeat has gone stale. */
    @Transactional
    public int sweepStaleRunningJobs() {
        Instant threshold = Instant.now().minusSeconds(STALE_HEARTBEAT_SECONDS);
        List<AiJob> stale = repository.findByStatusAndHeartbeatAtBefore(JobStatus.RUNNING, threshold);
        for (AiJob job : stale) {
            if (job.canRetry()) {
                job.requeueAfterStaleHeartbeat(nextRunAfter(job.getAttempts()));
            } else {
                job.markFailed("STALE_HEARTBEAT", "Worker stopped heartbeating and max attempts exhausted");
            }
        }
        return stale.size();
    }

    private Instant nextRunAfter(int attempts) {
        long base = BACKOFF_SECONDS[Math.min(Math.max(attempts - 1, 0), BACKOFF_SECONDS.length - 1)];
        long jitterMs = ThreadLocalRandom.current().nextLong(0, 1000);
        return Instant.now().plusSeconds(base).plusMillis(jitterMs);
    }
}
