package com.studyflow.jobs.service;

import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.repo.AiJobRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enqueue-time idempotency-key lookup, then input_fingerprint dedupe against SUCCEEDED jobs (see
 * specs/07-jobs-and-async.md). Quota checks happen in the caller, inside the same transaction,
 * before this runs.
 */
@Service
public class JobEnqueueService {

    private final AiJobRepository repository;

    public JobEnqueueService(AiJobRepository repository) {
        this.repository = repository;
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
                    .findFirstByInputFingerprintAndStatusOrderByCreatedAtDesc(fingerprint, JobStatus.SUCCEEDED);
            if (succeeded.isPresent()) {
                return succeeded.get();
            }
        }
        AiJob job = new AiJob(ownerId, taskType, paramsJson, fingerprint, idempotencyKey);
        return repository.save(job);
    }

    public static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
