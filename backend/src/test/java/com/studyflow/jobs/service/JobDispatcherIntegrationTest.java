package com.studyflow.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.domain.User;
import com.studyflow.identity.repo.UserRepository;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.domain.TransientJobException;
import com.studyflow.jobs.repo.AiJobClaimDao;
import com.studyflow.jobs.repo.AiJobRepository;
import com.studyflow.support.DatabaseCleanerExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@ExtendWith(DatabaseCleanerExtension.class)
class JobDispatcherIntegrationTest {

    @Autowired
    private AiJobClaimDao claimDao;
    @Autowired
    private AiJobRepository jobRepository;
    @Autowired
    private JobLifecycleService lifecycleService;
    @Autowired
    private UserRepository userRepository;

    // Each JobDispatcher owns its own thread pools; without cleanup they leak across the 4 test
    // methods sharing this class's cached Spring context, and the accumulated thread pressure
    // was flaky-failing later tests' polling-based assertions.
    private final List<JobDispatcher> dispatchersToClose = new ArrayList<>();

    @AfterEach
    void closeDispatchers() {
        dispatchersToClose.forEach(JobDispatcher::shutdown);
        dispatchersToClose.clear();
    }

    private JobDispatcher newDispatcher(JobHandler handler) {
        JobDispatcher dispatcher = new JobDispatcher(claimDao, jobRepository, lifecycleService, List.of(handler));
        dispatchersToClose.add(dispatcher);
        return dispatcher;
    }

    @Test
    void claimRunHeartbeatComplete() throws Exception {
        AiJob job = jobRepository.save(new AiJob(newOwner(), TaskType.DOCUMENT_INGEST, "{}", null, null));

        CountDownLatch handlerRan = new CountDownLatch(1);
        JobHandler fakeHandler = new JobHandler() {
            @Override
            public TaskType taskType() {
                return TaskType.DOCUMENT_INGEST;
            }

            @Override
            public String handle(AiJob j, ProgressReporter progress) {
                progress.report(50, "halfway");
                handlerRan.countDown();
                return "{\"ok\":true}";
            }
        };

        JobDispatcher dispatcher = newDispatcher(fakeHandler);
        UUID claimedId = dispatcher.pollOnce();
        assertThat(claimedId).isEqualTo(job.getId());
        assertThat(handlerRan.await(5, TimeUnit.SECONDS)).isTrue();

        AiJob finished = awaitTerminal(job.getId());
        assertThat(finished.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(finished.getProgressPct()).isEqualTo((short) 100);
        assertThat(finished.getResultRefJson()).contains("ok");
        assertThat(finished.getHeartbeatAt()).isNotNull();
        assertThat(finished.getAttempts()).isEqualTo(1);
        assertThat(finished.getStartedAt()).isNotNull();
        assertThat(finished.getFinishedAt()).isNotNull();
    }

    @Test
    void nonTransientFailureGoesStraightToFailedNoRetry() throws Exception {
        AiJob job = jobRepository.save(new AiJob(newOwner(), TaskType.DOCUMENT_INGEST, "{}", null, null));
        JobHandler failingHandler = new JobHandler() {
            @Override
            public TaskType taskType() {
                return TaskType.DOCUMENT_INGEST;
            }

            @Override
            public String handle(AiJob j, ProgressReporter progress) {
                throw new IllegalStateException("boom");
            }
        };

        JobDispatcher dispatcher = newDispatcher(failingHandler);
        dispatcher.pollOnce();

        AiJob finished = awaitTerminal(job.getId());
        assertThat(finished.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(finished.getErrorCode()).isEqualTo("HANDLER_ERROR");
        assertThat(finished.getAttempts()).isEqualTo(1);
    }

    @Test
    void transientFailureRequeuesForRetryInsteadOfFailing() throws Exception {
        AiJob job = jobRepository.save(new AiJob(newOwner(), TaskType.DOCUMENT_INGEST, "{}", null, null));
        AtomicInteger callCount = new AtomicInteger();
        JobHandler transientHandler = new JobHandler() {
            @Override
            public TaskType taskType() {
                return TaskType.DOCUMENT_INGEST;
            }

            @Override
            public String handle(AiJob j, ProgressReporter progress) {
                callCount.incrementAndGet();
                throw new TransientJobException("provider timeout");
            }
        };

        JobDispatcher dispatcher = newDispatcher(transientHandler);
        dispatcher.pollOnce();

        AiJob requeued = awaitStatus(job.getId(), JobStatus.QUEUED);
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(requeued.getAttempts()).isEqualTo(1);
        assertThat(requeued.getStatus()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void concurrentClaimsOnTheSameJobOnlyOneWorkerWins() throws Exception {
        AiJob job = jobRepository.save(new AiJob(newOwner(), TaskType.DOCUMENT_INGEST, "{}", null, null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Optional<AiJobClaimDao.ClaimedJob>>> futures = List.of(
                pool.submit(() -> {
                    go.await();
                    return claimDao.claimNextJobId();
                }),
                pool.submit(() -> {
                    go.await();
                    return claimDao.claimNextJobId();
                }));
        go.countDown();

        long winners = 0;
        try {
            for (Future<Optional<AiJobClaimDao.ClaimedJob>> future : futures) {
                if (future.get(5, TimeUnit.SECONDS).isPresent()) {
                    winners++;
                }
            }
        } finally {
            pool.shutdown();
        }

        assertThat(winners).isEqualTo(1);
        AiJob claimed = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.getAttempts()).isEqualTo(1);
    }

    private UUID newOwner() {
        User user = userRepository
                .save(new User("jobowner" + System.nanoTime() + "@example.com", "hash", "Job Owner", (short) 2000));
        return user.getId();
    }

    private AiJob awaitTerminal(UUID jobId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            AiJob job = jobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job did not reach a terminal state in time");
    }

    private AiJob awaitStatus(UUID jobId, JobStatus expected) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            AiJob job = jobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() == expected) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job did not reach status " + expected + " in time");
    }
}
