CREATE TABLE summaries (
    id             UUID PRIMARY KEY,
    document_id    UUID NOT NULL REFERENCES documents (id),
    owner_id       UUID NOT NULL REFERENCES users (id),
    summary_type   VARCHAR(20) NOT NULL DEFAULT 'QUICK' CHECK (summary_type IN ('QUICK')),
    content_md     TEXT NOT NULL,
    citations      JSONB NOT NULL DEFAULT '[]',
    model          VARCHAR(100) NOT NULL,
    prompt_version INTEGER NOT NULL,
    job_id         UUID REFERENCES ai_jobs (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX summaries_document_idx ON summaries (document_id, created_at DESC);
