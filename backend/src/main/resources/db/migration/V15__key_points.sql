-- Phase 3, checkpoint 1: extend the task_type CHECK for key points extraction (see
-- docs/DECISIONS.md). Flyway migrations are immutable once applied, so a new TaskType value
-- always widens this constraint via DROP/ADD in a new migration, same technique used for
-- `usage_counters.metric`.
ALTER TABLE ai_jobs DROP CONSTRAINT ai_jobs_task_type_check;
ALTER TABLE ai_jobs ADD CONSTRAINT ai_jobs_task_type_check
    CHECK (task_type IN ('DOCUMENT_INGEST', 'SUMMARY_GENERATE', 'KEY_POINTS_EXTRACT'));

-- Regeneration creates a new job_id batch — never overwrites (same posture as summaries).
CREATE TABLE key_points (
    id             UUID PRIMARY KEY,
    document_id    UUID NOT NULL REFERENCES documents (id),
    owner_id       UUID NOT NULL REFERENCES users (id),
    job_id         UUID NOT NULL REFERENCES ai_jobs (id),
    category       VARCHAR(20) NOT NULL
                       CHECK (category IN ('CONCEPT', 'DEFINITION', 'FORMULA', 'FACT', 'DATE')),
    label          TEXT NOT NULL,
    content_md     TEXT NOT NULL,
    citations      JSONB NOT NULL DEFAULT '[]',
    sort_order     SMALLINT NOT NULL,
    model          VARCHAR(100) NOT NULL,
    prompt_version INTEGER NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX key_points_document_job_idx ON key_points (document_id, job_id, sort_order);
CREATE INDEX key_points_owner_idx ON key_points (owner_id, created_at DESC);
