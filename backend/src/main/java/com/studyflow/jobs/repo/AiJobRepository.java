package com.studyflow.jobs.repo;

import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.JobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiJobRepository extends JpaRepository<AiJob, UUID> {

    Optional<AiJob> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * The job engine's own internals (claim/dispatch/heartbeat/progress) look up a job by id
     * with no owner in scope — the worker thread processes whichever job it atomically claimed,
     * not a specific user's request. The id here is always system-derived (from the claim
     * query), never client-supplied, so this isn't an IDOR risk the way a bare {@code findById}
     * reachable from a controller would be. A distinct method name (rather than the inherited
     * {@code findById}) keeps the ArchitectureTest's ban on bare findById meaningful — see
     * specs/02-data-model.md §Tenancy enforcement.
     */
    @Query("SELECT j FROM AiJob j WHERE j.id = :id")
    Optional<AiJob> findByIdForInternalProcessing(@Param("id") UUID id);

    Optional<AiJob> findByOwnerIdAndIdempotencyKey(UUID ownerId, String idempotencyKey);

    Optional<AiJob> findFirstByInputFingerprintAndStatusOrderByCreatedAtDesc(String inputFingerprint,
            JobStatus status);

    List<AiJob> findByStatusAndHeartbeatAtBefore(JobStatus status, Instant threshold);

    // Cursor pagination on UUIDv7 ids (time-sortable) — never offset-based, per
    // specs/03-api-and-errors.md.
    List<AiJob> findByOwnerIdOrderByIdDesc(UUID ownerId, Limit limit);

    List<AiJob> findByOwnerIdAndIdLessThanOrderByIdDesc(UUID ownerId, UUID cursor, Limit limit);
}
