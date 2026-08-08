-- Widen to fit a day-granularity key (YYYY-MM-DD) alongside the existing month-granularity keys
-- (YYYY-MM) — see docs/DECISIONS.md §Phase 2 retrieval parameters and grounding contract, quota.
ALTER TABLE usage_counters ALTER COLUMN period_ym TYPE VARCHAR(10);
ALTER TABLE usage_counters DROP CONSTRAINT usage_counters_metric_check;
ALTER TABLE usage_counters ADD CONSTRAINT usage_counters_metric_check
    CHECK (metric IN ('UPLOADS', 'AI_JOBS', 'TUTOR_MESSAGES'));
