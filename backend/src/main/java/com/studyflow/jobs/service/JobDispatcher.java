package com.studyflow.jobs.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.domain.TransientJobException;
import com.studyflow.jobs.repo.AiJobClaimDao;
import com.studyflow.jobs.repo.AiJobClaimDao.ClaimedJob;
import com.studyflow.jobs.repo.AiJobRepository;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for QUEUED jobs and runs them on a bounded worker pool (see
 * specs/07-jobs-and-async.md). {@code pollOnce()} is public so tests can trigger a claim
 * deterministically instead of waiting on the scheduler. Background polling is disabled in
 * tests ({@code studyflow.jobs.dispatcher.enabled=false}) — otherwise this bean's own scheduled
 * poll would race against tests that construct their own dispatcher with a fake handler.
 */
@Component
public class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);
    private static final int WORKER_POOL_SIZE = 4;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 10;

    private final AiJobClaimDao claimDao;
    private final AiJobRepository jobRepository;
    private final JobLifecycleService lifecycleService;
    private final Map<TaskType, JobHandler> handlers;
    private final ExecutorService workerPool = Executors.newFixedThreadPool(WORKER_POOL_SIZE);
    private final ScheduledExecutorService heartbeatPool = Executors.newScheduledThreadPool(2);
    // Claiming a job flips it to RUNNING in the DB immediately, so an unbounded claim would let
    // jobs pile up "RUNNING" in the DB while actually just waiting in workerPool's internal
    // queue — misreporting status and starving their heartbeat. Bound claims to actually-free
    // workers instead.
    private final Semaphore workerPermits = new Semaphore(WORKER_POOL_SIZE);

    @Value("${studyflow.jobs.dispatcher.enabled:true}")
    private boolean enabled = true;

    public JobDispatcher(AiJobClaimDao claimDao, AiJobRepository jobRepository,
            JobLifecycleService lifecycleService, List<JobHandler> handlerList) {
        this.claimDao = claimDao;
        this.jobRepository = jobRepository;
        this.lifecycleService = lifecycleService;
        this.handlers = handlerList.stream().collect(Collectors.toMap(JobHandler::taskType, Function.identity()));
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (!enabled) {
            return;
        }
        pollOnce();
    }

    /** Claims at most one job and submits it for processing. Returns the claimed job id, if any. */
    public UUID pollOnce() {
        if (!workerPermits.tryAcquire()) {
            return null;
        }
        Optional<ClaimedJob> claimed = claimDao.claimNextJobId();
        if (claimed.isEmpty()) {
            workerPermits.release();
            return null;
        }
        ClaimedJob job = claimed.get();
        workerPool.submit(() -> {
            try {
                process(job.id(), job.attempts());
            } finally {
                workerPermits.release();
            }
        });
        return job.id();
    }

    private void process(UUID jobId, int attempts) {
        AiJob job = jobRepository.findByIdForInternalProcessing(jobId).orElse(null);
        if (job == null) {
            return;
        }
        JobHandler handler = handlers.get(job.getTaskType());
        if (handler == null) {
            lifecycleService.markFailed(jobId, attempts, "NO_HANDLER", "No handler registered for " + job.getTaskType());
            return;
        }

        // initialDelay=0 so the first heartbeat lands immediately — a job whose handler takes
        // longer than STALE_HEARTBEAT_SECONDS before its first heartbeat would otherwise look
        // stale to the sweeper before it ever got one.
        ScheduledFuture<?> heartbeat = heartbeatPool.scheduleAtFixedRate(
                () -> lifecycleService.touchHeartbeat(jobId, attempts), 0, HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        try {
            ProgressReporter reporter = (pct, stage) -> lifecycleService.updateProgress(jobId, attempts, pct, stage);
            String resultRef = handler.handle(job, reporter);
            lifecycleService.markSucceeded(jobId, attempts, resultRef);
        } catch (TransientJobException e) {
            log.warn("Transient failure on job {}: {}", jobId, e.getMessage());
            lifecycleService.retryOrFail(jobId, attempts, "TRANSIENT_FAILURE", e.getMessage());
        } catch (ApiException e) {
            // Carries a stable ErrorCode (e.g. AI_SCHEMA_INVALID, AI_INSUFFICIENT_CONTEXT) —
            // surface it as-is rather than collapsing to a generic HANDLER_ERROR.
            log.warn("Job {} failed with {}: {}", jobId, e.code(), e.getMessage());
            lifecycleService.markFailed(jobId, attempts, e.code().name(), e.getMessage());
        } catch (Exception e) {
            log.error("Job {} failed", jobId, e);
            lifecycleService.markFailed(jobId, attempts, "HANDLER_ERROR", e.getMessage());
        } finally {
            heartbeat.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        workerPool.shutdown();
        heartbeatPool.shutdown();
    }
}
