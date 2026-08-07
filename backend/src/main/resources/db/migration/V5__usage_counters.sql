CREATE TABLE usage_counters (
    user_id   UUID NOT NULL REFERENCES users (id),
    period_ym VARCHAR(7) NOT NULL,
    metric    VARCHAR(20) NOT NULL CHECK (metric IN ('UPLOADS', 'AI_JOBS')),
    value     BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, period_ym, metric)
);
