package com.studyflow.jobs.service;

import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.repo.AiJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
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

    private static final Logger log = LoggerFactory.getLogger(JobLifecycleService.class);
    private static final long[] BACKOFF_SECONDS = {5, 20, 80};
    private static final long STALE_HEARTBEAT_SECONDS = 90;

    private final AiJobRepository repository;
    // Self-injected (via a lazily-resolved proxy) so sweepStaleRunningJobs's call to
    // recoverIfStillStale goes back through the Spring AOP proxy instead of a plain `this.`
    // call — a same-instance call bypasses proxy-based @Transactional entirely, which would
    // silently turn recoverIfStillStale's REQUIRES_NEW into "just joins whatever transaction
    // sweepStaleRunningJobs happened to have," defeating the per-row fresh-read/fresh-write it
    // depends on.
    private final JobLifecycleService self;

    public JobLifecycleService(AiJobRepository repository, @Lazy JobLifecycleService self) {
        this.repository = repository;
        this.self = self;
    }

    /**
     * Every write below is fenced on the attempt generation the caller was claimed with: each
     * runs in its own fresh REQUIRES_NEW transaction, re-reads the row, and only mutates when
     * the row is still RUNNING at that exact attempt count. If the sweeper has since requeued
     * and another worker reclaimed the job (bumping attempts, or leaving it non-RUNNING), a
     * stale worker's eventual heartbeat/progress/completion silently no-ops here instead of
     * clobbering the reclaiming worker's state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(UUID jobId, int attempts, int pct, String stage) {
        if (pct < 0 || pct > 100) {
            log.warn("Ignoring out-of-range progress {}% for job {}", pct, jobId);
            return;
        }
        runIfActiveAttempt(jobId, attempts, job -> job.reportProgress((short) pct, stage));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchHeartbeat(UUID jobId, int attempts) {
        runIfActiveAttempt(jobId, attempts, job -> job.reportProgress(job.getProgressPct(), job.getProgressStage()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(UUID jobId, int attempts, String resultRefJson) {
        runIfActiveAttempt(jobId, attempts, job -> job.markSucceeded(resultRefJson));
    }

    /** Non-transient failure (validation, quota exhaustion, unexpected error): straight to FAILED. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, int attempts, String errorCode, String errorMessage) {
        runIfActiveAttempt(jobId, attempts, job -> job.markFailed(errorCode, errorMessage));
    }

    /** Transient failure: retry with backoff if attempts remain, else FAILED. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryOrFail(UUID jobId, int attempts, String errorCode, String errorMessage) {
        runIfActiveAttempt(jobId, attempts, job -> {
            if (job.canRetry()) {
                job.requeueAfterStaleHeartbeat(nextRunAfter(job.getAttempts()));
            } else {
                job.markFailed(errorCode, errorMessage);
            }
        });
    }

    private void runIfActiveAttempt(UUID jobId, int attempts, Consumer<AiJob> mutation) {
        repository.findByIdForInternalProcessing(jobId)
                .filter(job -> job.getAttempts() == attempts && job.getStatus() == JobStatus.RUNNING)
                .ifPresent(mutation);
    }

    /** JobSweeper: requeues RUNNING jobs whose heartbeat has gone stale. */
    @Transactional
    public int sweepStaleRunningJobs() {
        Instant threshold = Instant.now().minusSeconds(STALE_HEARTBEAT_SECONDS);
        List<AiJob> stale = repository.findByStatusAndHeartbeatAtBefore(JobStatus.RUNNING, threshold);
        for (AiJob job : stale) {
            self.recoverIfStillStale(job.getId(), threshold);
        }
        return stale.size();
    }

    /**
     * Re-reads the job in its own fresh transaction and rechecks staleness at the moment of
     * write, not just at the original SELECT — a heartbeat touch that commits between this
     * sweep's SELECT and its UPDATE must not be clobbered by a requeue based on stale data.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recoverIfStillStale(UUID jobId, Instant threshold) {
        repository.findByIdForInternalProcessing(jobId)
                .filter(job -> job.getStatus() == JobStatus.RUNNING && job.getHeartbeatAt() != null
                        && job.getHeartbeatAt().isBefore(threshold))
                .ifPresent(job -> {
                    if (job.canRetry()) {
                        job.requeueAfterStaleHeartbeat(nextRunAfter(job.getAttempts()));
                    } else {
                        job.markFailed("STALE_HEARTBEAT", "Worker stopped heartbeating and max attempts exhausted");
                    }
                });
    }

    private Instant nextRunAfter(int attempts) {
        long base = BACKOFF_SECONDS[Math.min(Math.max(attempts - 1, 0), BACKOFF_SECONDS.length - 1)];
        long jitterMs = ThreadLocalRandom.current().nextLong(0, 1000);
        return Instant.now().plusSeconds(base).plusMillis(jitterMs);
    }
}
