CREATE TABLE ai_jobs (
    id                 UUID PRIMARY KEY,
    owner_id           UUID NOT NULL REFERENCES users (id),
    task_type          VARCHAR(40) NOT NULL
                           CHECK (task_type IN ('DOCUMENT_INGEST', 'SUMMARY_GENERATE')),
    status             VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
                           CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    priority           INTEGER NOT NULL DEFAULT 0,
    params             JSONB NOT NULL DEFAULT '{}',
    input_fingerprint  VARCHAR(64),
    idempotency_key    VARCHAR(255),
    result_ref         JSONB,
    progress_pct       SMALLINT NOT NULL DEFAULT 0,
    progress_stage     VARCHAR(100),
    attempts           INTEGER NOT NULL DEFAULT 0,
    max_attempts       INTEGER NOT NULL DEFAULT 3,
    run_after          TIMESTAMPTZ NOT NULL DEFAULT now(),
    heartbeat_at       TIMESTAMPTZ,
    error_code         VARCHAR(100),
    error_message      TEXT,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The claim query's index: SELECT ... WHERE status='QUEUED' AND run_after <= now()
-- ORDER BY priority DESC, created_at ASC FOR UPDATE SKIP LOCKED.
CREATE INDEX ai_jobs_claim_idx ON ai_jobs (priority DESC, created_at ASC) WHERE status = 'QUEUED';
CREATE INDEX ai_jobs_owner_id_idx ON ai_jobs (owner_id, created_at DESC);
CREATE INDEX ai_jobs_fingerprint_idx ON ai_jobs (input_fingerprint) WHERE status = 'SUCCEEDED';
CREATE UNIQUE INDEX ai_jobs_idempotency_idx ON ai_jobs (owner_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
