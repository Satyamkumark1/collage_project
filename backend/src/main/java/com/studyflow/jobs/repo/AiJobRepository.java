package com.studyflow.jobs.repo;

import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiJobRepository extends JpaRepository<AiJob, UUID> {

    Optional<AiJob> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<AiJob> findByOwnerIdAndIdempotencyKey(UUID ownerId, String idempotencyKey);

    Optional<AiJob> findFirstByInputFingerprintAndStatusOrderByCreatedAtDesc(String inputFingerprint,
            JobStatus status);

    List<AiJob> findByStatusAndHeartbeatAtBefore(JobStatus status, Instant threshold);

    // Cursor pagination on UUIDv7 ids (time-sortable) — never offset-based, per
    // specs/03-api-and-errors.md.
    List<AiJob> findByOwnerIdOrderByIdDesc(UUID ownerId, Limit limit);

    List<AiJob> findByOwnerIdAndIdLessThanOrderByIdDesc(UUID ownerId, UUID cursor, Limit limit);
}
