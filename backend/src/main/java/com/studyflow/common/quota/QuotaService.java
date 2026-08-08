package com.studyflow.common.quota;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quota enforcement, atomic increment via {@code INSERT ... ON CONFLICT DO UPDATE} (see
 * specs/12-billing-and-quotas.md). Always called from inside the caller's own transaction —
 * enqueue time / message-send time, before any money is spent — so a thrown quota exception rolls
 * back the increment along with whatever else the caller was about to create. Period granularity
 * is per-metric: monthly for uploads/AI jobs, daily for tutor messages (see docs/DECISIONS.md) —
 * {@code usage_counters.period_ym} just stores whichever period key the caller asks for.
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
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbcTemplate;

    public QuotaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void incrementAndEnforce(UUID userId, UsageMetric metric, long monthlyLimit, ErrorCode exceededCode) {
        incrementAndEnforceForPeriod(userId, metric, YearMonth.now(QUOTA_ZONE).toString(), monthlyLimit,
                exceededCode, "Monthly");
    }

    @Transactional
    public void incrementAndEnforceDaily(UUID userId, UsageMetric metric, long dailyLimit, ErrorCode exceededCode) {
        incrementAndEnforceForPeriod(userId, metric, LocalDate.now(QUOTA_ZONE).toString(), dailyLimit, exceededCode,
                "Daily");
    }

    private void incrementAndEnforceForPeriod(UUID userId, UsageMetric metric, String period, long limit,
            ErrorCode exceededCode, String periodLabel) {
        Long newValue = jdbcTemplate.queryForObject(UPSERT_SQL, Long.class, userId, period, metric.name());
        if (newValue != null && newValue > limit) {
            throw new ApiException(exceededCode, periodLabel + " quota exceeded for " + metric);
        }
    }
}
