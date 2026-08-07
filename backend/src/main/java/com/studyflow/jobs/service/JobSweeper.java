package com.studyflow.jobs.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Requeues jobs stuck RUNNING with a stale heartbeat (see specs/07-jobs-and-async.md). Disabled
 * in tests ({@code studyflow.jobs.sweeper.enabled=false}) — same reasoning as JobDispatcher.
 */
@Component
public class JobSweeper {

    private static final Logger log = LoggerFactory.getLogger(JobSweeper.class);

    private final JobLifecycleService lifecycleService;

    @Value("${studyflow.jobs.sweeper.enabled:true}")
    private boolean enabled = true;

    public JobSweeper(JobLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelay = 30000)
    public void sweep() {
        if (!enabled) {
            return;
        }
        int swept = lifecycleService.sweepStaleRunningJobs();
        if (swept > 0) {
            log.warn("Swept {} stale RUNNING job(s) back to QUEUED/FAILED", swept);
        }
    }
}
