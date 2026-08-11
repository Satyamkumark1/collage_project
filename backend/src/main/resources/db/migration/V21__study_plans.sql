-- Phase 7: study planner. Insert-only, same posture as question_sets/quizzes — a new exam date
-- makes a new plan. No status/completion tracking on sessions (not asked for, see docs/DECISIONS.md).
CREATE TABLE study_plans (
    id           UUID PRIMARY KEY,
    document_id  UUID NOT NULL REFERENCES documents (id),
    owner_id     UUID NOT NULL REFERENCES users (id),
    exam_date    DATE NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX study_plans_document_owner_idx ON study_plans (document_id, owner_id, created_at DESC);

CREATE TABLE study_sessions (
    id              UUID PRIMARY KEY,
    plan_id         UUID NOT NULL REFERENCES study_plans (id),
    document_id     UUID NOT NULL REFERENCES documents (id),
    owner_id        UUID NOT NULL REFERENCES users (id),
    scheduled_date  DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX study_sessions_plan_owner_idx ON study_sessions (plan_id, owner_id);
