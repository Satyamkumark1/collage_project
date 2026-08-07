package com.studyflow.jobs.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The claim query from specs/07-jobs-and-async.md, run via plain JdbcTemplate (not a Spring Data
 * derived/native query) so its transaction boundary is explicit and short — this must commit and
 * release its row lock immediately, before the (possibly minutes-long) handler runs, not hold a
 * lock for the job's whole duration.
 */
@Repository
public class AiJobClaimDao {

    private static final String CLAIM_SQL = """
            UPDATE ai_jobs SET status = 'RUNNING', heartbeat_at = now(),
              started_at = coalesce(started_at, now()), attempts = attempts + 1
            WHERE id = (
              SELECT id FROM ai_jobs
              WHERE status = 'QUEUED' AND run_after <= now()
              ORDER BY priority DESC, created_at ASC
              FOR UPDATE SKIP LOCKED
              LIMIT 1
            )
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;

    public AiJobClaimDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> claimNextJobId() {
        List<UUID> ids = jdbcTemplate.query(CLAIM_SQL, (rs, rowNum) -> (UUID) rs.getObject("id"));
        return ids.stream().findFirst();
    }
}
