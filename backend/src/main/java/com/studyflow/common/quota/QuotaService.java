package com.studyflow.common.quota;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly quota enforcement, atomic increment via {@code INSERT ... ON CONFLICT DO UPDATE} (see
 * specs/12-billing-and-quotas.md). Always called from inside the caller's own transaction —
 * enqueue time, before any money is spent — so a thrown quota exception rolls back the
 * increment along with whatever else the caller was about to create.
 */
@Service
public class QuotaService {

    private static final String UPSERT_SQL = """
            INSERT INTO usage_counters (user_id, period_ym, metric, value)
            VALUES (?, ?, ?, 1)
            ON CONFLICT (user_id, period_ym, metric)
            DO UPDATE SET value = usage_counters.value + 1
            RETURNING value
            """;

    private final JdbcTemplate jdbcTemplate;

    public QuotaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void incrementAndEnforce(UUID userId, UsageMetric metric, long monthlyLimit, ErrorCode exceededCode) {
        String periodYm = YearMonth.now(ZoneId.of("Asia/Kolkata")).toString();
        Long newValue = jdbcTemplate.queryForObject(UPSERT_SQL, Long.class, userId, periodYm, metric.name());
        if (newValue != null && newValue > monthlyLimit) {
            throw new ApiException(exceededCode, "Monthly quota exceeded for " + metric);
        }
    }
}
