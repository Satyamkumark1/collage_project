-- Phase 3, checkpoint 2: extend the task_type CHECK for MCQ batch generation (see
-- docs/DECISIONS.md). Same DROP/ADD technique as V15.
ALTER TABLE ai_jobs DROP CONSTRAINT ai_jobs_task_type_check;
ALTER TABLE ai_jobs ADD CONSTRAINT ai_jobs_task_type_check
    CHECK (task_type IN ('DOCUMENT_INGEST', 'SUMMARY_GENERATE', 'KEY_POINTS_EXTRACT', 'MCQ_GENERATE'));

-- One question_sets row per batch (10/25/50 requested); generated_count can be < requested_count
-- on partial success — see docs/DECISIONS.md and docs/status/phase-3.md.
CREATE TABLE question_sets (
    id               UUID PRIMARY KEY,
    document_id      UUID NOT NULL REFERENCES documents (id),
    owner_id         UUID NOT NULL REFERENCES users (id),
    job_id           UUID REFERENCES ai_jobs (id),
    requested_count  SMALLINT NOT NULL CHECK (requested_count IN (10, 25, 50)),
    generated_count  SMALLINT NOT NULL,
    difficulty_mix   JSONB NOT NULL,
    model            VARCHAR(100) NOT NULL,
    prompt_version   INTEGER NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX question_sets_document_owner_idx ON question_sets (document_id, owner_id, created_at DESC);

CREATE TABLE questions (
    id              UUID PRIMARY KEY,
    question_set_id UUID NOT NULL REFERENCES question_sets (id),
    document_id     UUID NOT NULL REFERENCES documents (id),
    owner_id        UUID NOT NULL REFERENCES users (id),
    stem            TEXT NOT NULL,
    options         JSONB NOT NULL,
    correct_index   SMALLINT NOT NULL CHECK (correct_index BETWEEN 0 AND 3),
    explanation     TEXT NOT NULL,
    difficulty      VARCHAR(10) NOT NULL CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    bloom_level     VARCHAR(20) NOT NULL
                        CHECK (bloom_level IN ('REMEMBER', 'UNDERSTAND', 'APPLY', 'ANALYZE')),
    citations       JSONB NOT NULL DEFAULT '[]',
    sort_order      SMALLINT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX questions_set_idx ON questions (question_set_id, sort_order);
CREATE INDEX questions_document_owner_idx ON questions (document_id, owner_id, created_at DESC);
