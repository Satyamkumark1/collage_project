# Data Model

Postgres 16 (locally: Postgres 15 via Homebrew, see `/docs/DECISIONS.md`) + `pgvector`. UUIDv7
primary keys (time-sortable — helps cursor pagination and index locality). All timestamps
`timestamptz`, UTC. Soft delete via `deleted_at` on user content.

Tables marked **[now]** are built in the current phase (see `ROADMAP.md`). Tables marked
**[deferred]** are documented here for shape continuity but not created yet — when they are
created, they should match these column lists so nothing has to be redesigned.

## Tables — this phase

**users** [now] — `id`, `email` (citext, unique), `password_hash` (BCrypt cost 12), `name`,
`role` (`USER`/`ADMIN`), `email_verified_at`, `birth_year` (smallint, DPDP age gate),
`guardian_consent_at`, `status` (`ACTIVE`/`SUSPENDED`/`DELETION_PENDING`), `locale`, `timezone`
(default `Asia/Kolkata`), `last_login_at`, `created_at`, `updated_at`, `deleted_at`.

**refresh_tokens** [now] — `id`, `user_id`, `token_hash` (SHA-256; never store raw), `family_id`,
`expires_at`, `revoked_at`, `replaced_by`, `user_agent_hash`, `ip_hash`, `created_at`. Rotation
with reuse detection: presenting a revoked token revokes the whole `family_id`.

**documents** [now] — `id`, `owner_id`, `title`, `original_filename`, `mime_type`, `file_type`
(`PDF`/`TXT`/`MD` this phase; `DOCX`/`PPTX` deferred), `size_bytes`, `content_sha256`,
`storage_key`, `storage_provider`, `page_count`, `char_count`, `language`, `status`
(`UPLOADED`/`PARSING`/`CHUNKING`/`EMBEDDING`/`READY`/`FAILED`), `failure_code`, `failure_detail`,
`created_at`, `updated_at`, `deleted_at`. Unique `(owner_id, content_sha256) WHERE deleted_at IS
NULL` — re-uploading the same file reuses the existing document.

**document_chunks** [now] — `id`, `document_id`, `owner_id` (denormalised for query scoping),
`chunk_index`, `content`, `token_count`, `page_from`, `page_to`, `section_path` (e.g. `Unit 3 ›
Normalization › BCNF`), `content_sha256`, `created_at`.

**chunk_embeddings** [now] — `chunk_id` (PK/FK), `document_id`, `owner_id`, `embedding`
(`vector(N)`, N = Voyage `voyage-4-lite` dimension, confirmed live before migrating — see
`/docs/DECISIONS.md`), `model`, `model_version`, `created_at`. Separate table so re-embedding with
a new model never rewrites chunk text.

**ai_jobs** [now] — `id`, `owner_id`, `task_type` (`DOCUMENT_INGEST`/`SUMMARY_GENERATE` this
phase), `status`, `priority`, `params` (jsonb), `input_fingerprint`, `idempotency_key`,
`result_ref` (jsonb pointer to produced entity), `progress_pct`, `progress_stage`, `attempts`,
`max_attempts`, `run_after`, `heartbeat_at`, `error_code`, `error_message`, `started_at`,
`finished_at`, `created_at`.

**ai_calls** [now] — `id`, `owner_id`, `job_id`, `provider`, `model`, `purpose`, `prompt_version`,
`tokens_in`, `tokens_out`, `latency_ms`, `cost_micro_inr`, `finish_reason`, `outcome`
(`OK`/`SCHEMA_FAIL`/`REPAIRED`/`REFUSED`/`ERROR`), `attempt_no`, `created_at`. This is the cost
ledger, abuse detector, and quality dashboard — written on every call.

**summaries** [now] — `id`, `document_id`, `owner_id`, `summary_type` (`QUICK` this phase;
`DETAILED`/`SECTIONWISE` deferred), `content_md`, `citations` (jsonb array of chunk refs),
`model`, `prompt_version`, `job_id`, `created_at`.

**usage_counters** [now] — `user_id`, `period_ym` (e.g. `2026-08`), `metric`
(`uploads`/`ai_jobs` this phase; `tokens_in`/`tokens_out`/`storage_bytes` deferred), `value`
(bigint). PK `(user_id, period_ym, metric)`. Incremented atomically via `INSERT ... ON CONFLICT
DO UPDATE`.

**quizzes** [now, Phase 4] — `id`, `document_id`, `owner_id`, `question_set_id` (the fresh MCQ
batch this quiz wraps — see [10-study-features.md](10-study-features.md)), `job_id`, `mode`
(`PRACTICE`/`EXAM`/`REVISION`), `question_count`, `time_limit_seconds` (nullable — `NULL` for
untimed REVISION), `negative_marking_fraction`, `created_at`. Insert-only, same posture as
`question_sets`.

**quiz_attempts** [now, Phase 4] — `id`, `quiz_id`, `owner_id`, `status`
(`IN_PROGRESS`/`SUBMITTED`/`EXPIRED`), `started_at`, `deadline_at` (nullable), `submitted_at`,
`score`, `max_score`, `correct_count`, `incorrect_count`, `unanswered_count`, `version` (JPA
optimistic lock — same rationale as `flashcards`), `created_at`.

**quiz_answers** [now, Phase 4] — `id`, `attempt_id`, `question_id`, `owner_id`, `selected_index`
(nullable — an explicit clear), `answered_at`. Unique `(attempt_id, question_id)`, upserted
incrementally as the student answers.

**study_plans** [now, Phase 7] — `id`, `document_id`, `owner_id`, `exam_date`, `created_at`.
Insert-only, same posture as `quizzes` — a new exam date makes a new plan.

**study_sessions** [now, Phase 7] — `id`, `plan_id`, `document_id`, `owner_id`, `scheduled_date`,
`created_at`. No status/completion tracking — see `docs/DECISIONS.md`.

## Tables — deferred (shape reference only, not created yet)

**email_tokens** [deferred] — verify/reset email flow; auto-verify is used instead this phase.

**plans** [deferred] — `code`, `name`, `price_inr_paise`, `interval`, `limits` (jsonb),
`razorpay_plan_id`, `active`.

**subscriptions** [deferred] — Razorpay-backed subscription state.

**question_sets, questions** [now, Phase 3] — MCQ batches; `quizzes.question_set_id` above
references `question_sets` directly, so these can't be deferred once Phase 4 exists. Full column
lists in [10-study-features.md](10-study-features.md) (not yet transcribed into this file's
"this phase" section above — a pre-existing Phase 3 documentation gap, out of scope here).

**key_points, flashcards** [deferred] — also actually built in Phase 3 (same gap as above); see
[10-study-features.md](10-study-features.md) for full column lists.

**conversations, messages** [deferred] — tutor chat, see [09-rag.md](09-rag.md) grounding
contract for the shape these need.

**exports** [deferred] — PDF/DOCX render output metadata.

**audit_events** [deferred] — append-only admin/security audit log.

## Indexes (this phase)

- `documents (owner_id, created_at DESC) WHERE deleted_at IS NULL`
- `document_chunks (document_id, chunk_index)`
- `chunk_embeddings` — HNSW on `embedding` with `vector_cosine_ops`, `m=16`,
  `ef_construction=64`. `hnsw.ef_search=40` per session at query time (once retrieval exists).
- `ai_jobs (status, priority DESC, created_at) WHERE status = 'QUEUED'` — the claim index.
- `ai_jobs (owner_id, created_at DESC)`
- `ai_jobs (input_fingerprint) WHERE status = 'SUCCEEDED'`
- `ai_calls (owner_id, created_at DESC)`
- `quizzes (document_id, owner_id, created_at DESC)`
- `quiz_attempts (owner_id, started_at DESC)`, `quiz_attempts (quiz_id, owner_id)`
- `quiz_answers (attempt_id, owner_id)`
- `study_plans (document_id, owner_id, created_at DESC)`
- `study_sessions (plan_id, owner_id)`
- Every foreign key gets an index on the child side.

## Tenancy enforcement

Application-layer scoping is mandatory and structurally enforced, not remembered: an ArchUnit
test (see [07-jobs-and-async.md](07-jobs-and-async.md) is unrelated; the rule itself lives in
`backend/src/test/java/com/studyflow/ArchitectureTest.java`) fails the build if any owner-scoped
repository's bare `findById` is called from outside its own package — callers must use
`findByIdAndOwnerId(id, ownerId)`. Covers, this phase: `DocumentRepository`,
`DocumentChunkRepository`, `AiJobRepository`, `AiCallRepository`, `SummaryRepository`. (This list
predates Phase 2/3's own owner-scoped repositories — see `ArchitectureTest` itself for the
authoritative, currently-enforced set: 32 rules as of Phase 7, covering every owner-scoped
repository added through `StudyPlanRepository`/`StudySessionRepository`.)

Postgres RLS as defence-in-depth is noted in the original spec but not implemented this phase —
would need verifying the connection pooler preserves `SET LOCAL app.user_id` within a transaction,
which only matters once a pooled remote Postgres (Neon) is in play.
