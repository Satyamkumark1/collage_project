-- Phase 4, checkpoint 1: extend the task_type CHECK for quiz build (see docs/DECISIONS.md). Same
-- DROP/ADD technique as V15/V16/V17.
ALTER TABLE ai_jobs DROP CONSTRAINT ai_jobs_task_type_check;
ALTER TABLE ai_jobs ADD CONSTRAINT ai_jobs_task_type_check
    CHECK (task_type IN ('DOCUMENT_INGEST', 'SUMMARY_GENERATE', 'KEY_POINTS_EXTRACT', 'MCQ_GENERATE',
        'FLASHCARD_GENERATE', 'QUIZ_BUILD'));

-- A quiz is a thin wrapper around a fresh MCQ batch (question_sets/questions, reusing
-- McqGenerationService unchanged) plus timing/scoring config — see docs/DECISIONS.md. Insert-only,
-- same posture as question_sets itself; regeneration creates a new quiz row.
CREATE TABLE quizzes (
    id                          UUID PRIMARY KEY,
    document_id                 UUID NOT NULL REFERENCES documents (id),
    owner_id                    UUID NOT NULL REFERENCES users (id),
    question_set_id             UUID NOT NULL REFERENCES question_sets (id),
    job_id                      UUID REFERENCES ai_jobs (id),
    mode                        VARCHAR(10) NOT NULL CHECK (mode IN ('PRACTICE', 'EXAM', 'REVISION')),
    question_count              SMALLINT NOT NULL,
    time_limit_seconds          INTEGER,
    negative_marking_fraction   NUMERIC(3,2) NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX quizzes_document_owner_idx ON quizzes (document_id, owner_id, created_at DESC);

-- Mutable (status/score change on submit), same @Version optimistic-locking posture as
-- flashcards — a concurrent submit-vs-lazy-expire race on the same attempt must fail loudly, not
-- silently double-score. See docs/DECISIONS.md.
CREATE TABLE quiz_attempts (
    id                UUID PRIMARY KEY,
    quiz_id           UUID NOT NULL REFERENCES quizzes (id),
    owner_id          UUID NOT NULL REFERENCES users (id),
    status            VARCHAR(12) NOT NULL CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'EXPIRED')),
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deadline_at       TIMESTAMPTZ,
    submitted_at      TIMESTAMPTZ,
    score             NUMERIC(6,2),
    max_score         SMALLINT,
    correct_count     SMALLINT,
    incorrect_count   SMALLINT,
    unanswered_count  SMALLINT,
    version           INTEGER NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX quiz_attempts_owner_idx ON quiz_attempts (owner_id, started_at DESC);
CREATE INDEX quiz_attempts_quiz_owner_idx ON quiz_attempts (quiz_id, owner_id);

-- Incremental per-question answer saves. Upserted by (attempt_id, question_id) from the service
-- layer (find-then-update-or-insert) rather than a DB-level ON CONFLICT — low contention, one
-- student editing their own attempt.
CREATE TABLE quiz_answers (
    id              UUID PRIMARY KEY,
    attempt_id      UUID NOT NULL REFERENCES quiz_attempts (id),
    question_id     UUID NOT NULL REFERENCES questions (id),
    owner_id        UUID NOT NULL REFERENCES users (id),
    selected_index  SMALLINT CHECK (selected_index BETWEEN 0 AND 3),
    answered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (attempt_id, question_id)
);

CREATE INDEX quiz_answers_attempt_owner_idx ON quiz_answers (attempt_id, owner_id);
