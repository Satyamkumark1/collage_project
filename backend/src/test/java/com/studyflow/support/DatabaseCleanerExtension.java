package com.studyflow.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Truncates all tables after each test method. Integration tests here can't use Spring's usual
 * per-test @Transactional rollback trick, because job-processing tests deliberately run work on
 * background worker threads with their own DB connections — a still-open, not-yet-committed
 * outer transaction would be invisible to them. See docs/DECISIONS.md (local Postgres instead of
 * Testcontainers) for why this is a real DatabaseCleaner and not container-per-test isolation.
 */
public class DatabaseCleanerExtension implements AfterEachCallback {

    private static final String TRUNCATE_SQL = "TRUNCATE TABLE ai_jobs, documents, refresh_tokens, "
            + "usage_counters, messages, conversations, users RESTART IDENTITY CASCADE";

    @Override
    public void afterEach(ExtensionContext context) {
        JdbcTemplate jdbcTemplate = SpringExtension.getApplicationContext(context).getBean(JdbcTemplate.class);
        jdbcTemplate.execute(TRUNCATE_SQL);
    }
}
