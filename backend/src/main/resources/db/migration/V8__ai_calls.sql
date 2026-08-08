CREATE TABLE ai_calls (
    id             UUID PRIMARY KEY,
    owner_id       UUID NOT NULL REFERENCES users (id),
    job_id         UUID REFERENCES ai_jobs (id),
    provider       VARCHAR(20) NOT NULL,
    model          VARCHAR(100) NOT NULL,
    purpose        VARCHAR(50) NOT NULL,
    prompt_version INTEGER NOT NULL,
    tokens_in      INTEGER,
    tokens_out     INTEGER,
    latency_ms     INTEGER,
    cost_micro_inr BIGINT,
    finish_reason  VARCHAR(30),
    outcome        VARCHAR(20) NOT NULL CHECK (outcome IN ('OK', 'SCHEMA_FAIL', 'REPAIRED', 'REFUSED', 'ERROR')),
    attempt_no     INTEGER NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ai_calls_owner_created_idx ON ai_calls (owner_id, created_at DESC);
CREATE INDEX ai_calls_job_idx ON ai_calls (job_id);
