-- Phase 3, checkpoint 3: extend the task_type CHECK for flashcard batch generation (see
-- docs/DECISIONS.md). Same DROP/ADD technique as V15/V16.
ALTER TABLE ai_jobs DROP CONSTRAINT ai_jobs_task_type_check;
ALTER TABLE ai_jobs ADD CONSTRAINT ai_jobs_task_type_check
    CHECK (task_type IN ('DOCUMENT_INGEST', 'SUMMARY_GENERATE', 'KEY_POINTS_EXTRACT', 'MCQ_GENERATE',
        'FLASHCARD_GENERATE'));

-- The first mutable row in study/ — every prior batch-study table (summaries, key_points,
-- question_sets/questions) is insert-only. SM-2 review state is genuinely mutated on each
-- review, so this table gets a `version` column for JPA optimistic locking (see
-- docs/DECISIONS.md's SM-2 entry) — a double-tapped review on a flaky connection should fail
-- loudly, not silently corrupt spaced-repetition state, same lesson as the Phase 1/2
-- concurrent-refresh-token bug.
CREATE TABLE flashcards (
    id                UUID PRIMARY KEY,
    document_id       UUID NOT NULL REFERENCES documents (id),
    owner_id          UUID NOT NULL REFERENCES users (id),
    job_id            UUID REFERENCES ai_jobs (id),
    front_md          TEXT NOT NULL,
    back_md           TEXT NOT NULL,
    citations         JSONB NOT NULL DEFAULT '[]',
    ease_factor       NUMERIC(4,2) NOT NULL DEFAULT 2.5,
    interval_days     INTEGER NOT NULL DEFAULT 0,
    repetitions       SMALLINT NOT NULL DEFAULT 0,
    due_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_reviewed_at  TIMESTAMPTZ,
    last_quality      SMALLINT CHECK (last_quality BETWEEN 0 AND 5),
    model             VARCHAR(100) NOT NULL,
    prompt_version    INTEGER NOT NULL,
    version           INTEGER NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX flashcards_document_owner_idx ON flashcards (document_id, owner_id, created_at DESC);
CREATE INDEX flashcards_owner_due_idx ON flashcards (owner_id, due_at);
