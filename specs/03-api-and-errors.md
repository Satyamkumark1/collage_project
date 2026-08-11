# API Conventions & Error Model

## Conventions

- Base path `/api/v1`. Never break v1; add v2 if needed.
- Cursor pagination: `?limit=20&cursor=<opaque>`. Response includes `nextCursor`. No offset
  pagination anywhere.
- List endpoints support `?sort=` from a fixed allowlist. Never accept raw column names.
- `Idempotency-Key` required on every POST that creates a job or a payment.
- `X-Request-Id` accepted from client, generated if absent, echoed on every response, present in
  every log line for that request.
- Timestamps ISO-8601 UTC. Money in paise as integers, never floats (billing is deferred this
  phase, but the convention holds when it lands).

## Error model

Every error, without exception, returns RFC 9457 `application/problem+json`:

```
type, title, status, detail, instance, code, requestId, errors[]?, retryAfterSeconds?
```

`code` is a stable machine-readable string the frontend switches on. Human-facing text lives in
the frontend, keyed by `code` — never render `detail` directly to a user.

### Error codes in use this phase

| Code | Status | Meaning |
|---|---|---|
| `AUTH_INVALID_CREDENTIALS` | 401 | Bad email/password |
| `AUTH_TOKEN_EXPIRED` | 401 | Access token expired — client should refresh |
| `AUTH_REFRESH_REUSED` | 401 | Token family revoked; force re-login |
| `AUTH_GUARDIAN_CONSENT_REQUIRED` | 403 | Under-18 without consent |
| `FILE_TYPE_UNSUPPORTED` | 415 | Not in the allowlist (PDF/TXT/MD this phase) |
| `FILE_TOO_LARGE` | 413 | Over configured limit |
| `FILE_ENCRYPTED` | 422 | Password-protected PDF |
| `FILE_NO_TEXT_LAYER` | 422 | Scanned image PDF — actionable message |
| `FILE_CORRUPT` | 422 | Parser failed |
| `DOCUMENT_NOT_READY` | 409 | Ingestion still running |
| `JOB_NOT_FOUND` | 404 | |
| `AI_PROVIDER_UNAVAILABLE` | 503 | Groq call failed after retries |
| `AI_SCHEMA_INVALID` | 422 | Model output unrepairable after one repair call |
| `AI_INSUFFICIENT_CONTEXT` | 422 | Not enough grounded material (used once retrieval exists) |
| `QUOTA_UPLOADS_EXCEEDED` | 402 | Monthly upload cap hit |
| `QUOTA_AI_EXCEEDED` | 402 | AI quota exceeded for monthly jobs or daily tutor messages |
| `VALIDATION_FAILED` | 400 | Request body failed bean validation |
| `AUTH_EMAIL_ALREADY_REGISTERED` | 409 | Email already has an account (not in original spec — added during build, see `/docs/DECISIONS.md`) |
| `DOCUMENT_NOT_FOUND` | 404 | Owner-scoped GET/DELETE found no matching document (added during build) |
| `SUMMARY_NOT_FOUND` | 404 | Owner-scoped GET found no matching summary (added during build) |
| `CONVERSATION_NOT_FOUND` | 404 | Owner-scoped GET found no matching tutor conversation (added during build, Phase 2) |
| `KEY_POINTS_NOT_FOUND` | 404 | Owner-scoped GET found no matching key-points batch (added during build, Phase 3) |
| `QUESTION_SET_NOT_FOUND` | 404 | Owner-scoped GET found no matching MCQ question set (added during build, Phase 3) |
| `FLASHCARD_NOT_FOUND` | 404 | Owner-scoped GET found no matching flashcard (added during build, Phase 3) |
| `QUIZ_NOT_FOUND` | 404 | Owner-scoped GET found no matching quiz (added during build, Phase 4) |
| `QUIZ_ATTEMPT_NOT_FOUND` | 404 | Owner-scoped GET found no matching quiz attempt (added during build, Phase 4) |
| `QUIZ_ATTEMPT_EXPIRED` | 409 | EXAM-mode answer write attempted after the server-authoritative deadline; the attempt was auto-finalized (added during build, Phase 4 — not in original spec, which couldn't have anticipated the concrete timing-enforcement mechanics) |
| `QUIZ_ATTEMPT_NOT_IN_PROGRESS` | 409 | Answer write attempted on an attempt that's already terminal (`SUBMITTED`/`EXPIRED`) (added during build, Phase 4) |
| `QUIZ_ATTEMPT_NOT_SUBMITTED` | 409 | Result requested while the attempt is still `IN_PROGRESS` — `EXPIRED`/`SUBMITTED` attempts are allowed; distinct from `QUIZ_ATTEMPT_NOT_IN_PROGRESS` because it's the opposite attempt-state condition (added during build, Phase 4) |
| `STUDY_PLAN_NOT_FOUND` | 404 | Owner-scoped GET found no matching study plan (added during build, Phase 7) |
| `NOT_FOUND` | 404 | Unmapped route (added during build) |
| `INTERNAL_ERROR` | 500 | Unhandled exception, never leaks detail (added during build) |

`TUTOR_OUT_OF_SCOPE` is **not implemented** despite being reserved for this phase — nothing in the
spec defines what "out of scope" means beyond "not grounded in the student's notes," which the
confidence-floor refusal already handles as a normal (non-error) chat message. See
docs/DECISIONS.md. Codes reserved for later phases still (`AUTH_EMAIL_UNVERIFIED`, `RATE_LIMITED`,
`PAYMENT_SIGNATURE_INVALID`, etc.) are listed in the original spec and will be added to this table
when the features that raise them are built.

## Endpoint map — this phase

**Auth**
- `POST /auth/register` `{email, password, name, birthYear}` → 201
- `POST /auth/login` `{email, password}` → 200 `{accessToken}` + HttpOnly refresh cookie
- `POST /auth/refresh` (cookie) → 200 `{accessToken}`, rotates cookie
- `POST /auth/logout` → 204, revokes refresh token family
- `GET /me` → 200

**Library**
- `POST /documents` (multipart: file, title — direct upload, see
  [05-library-and-storage.md](05-library-and-storage.md) for why this deviates from the spec's
  presigned-upload flow) → 202 `{documentId, jobId}`
- `GET /documents` (cursor-paginated) · `GET /documents/{id}` · `DELETE /documents/{id}`
  (soft delete)

**Study**
- `POST /documents/{id}/summaries` (`Idempotency-Key` required) → 202 `{jobId}`
- `GET /documents/{id}/summaries` · `GET /summaries/{id}`
- `POST /documents/{id}/key-points` (`Idempotency-Key` required) → 202 `{jobId}` (Phase 3)
- `GET /documents/{id}/key-points` · `GET /key-points/{id}`
- `POST /documents/{id}/question-sets` `{requestedCount}` (`Idempotency-Key` required) → 202
  `{jobId}` (Phase 3, MCQs)
- `GET /documents/{id}/question-sets` · `GET /question-sets/{id}` ·
  `GET /question-sets/{id}/questions` (answer key included — self-study review, not a scored
  attempt)
- `POST /documents/{id}/flashcards` (`Idempotency-Key` required) → 202 `{jobId}` (Phase 3)
- `GET /documents/{id}/flashcards` · `GET /flashcards/due?limit=` ·
  `POST /flashcards/{id}/review` `{quality}` → 200 (synchronous, no `Idempotency-Key` — pure SM-2
  arithmetic, no LLM call)
- `POST /documents/{id}/quizzes` `{mode, requestedCount}` (`Idempotency-Key` required) → 202
  `{jobId}` (Phase 4 — reuses MCQ generation, see [10-study-features.md](10-study-features.md))
- `GET /documents/{id}/quizzes` · `GET /quizzes/{id}` · `GET /quizzes/{id}/questions`
  (answer-key-free — see docs/DECISIONS.md)
- `POST /quizzes/{id}/attempts` → 201 · `GET /quizzes/{id}/attempts` (history)
- `GET /quiz-attempts/{id}` ·
  `PUT /quiz-attempts/{id}/answers/{questionId}` `{selectedIndex}` (nullable, to clear) ·
  `GET /quiz-attempts/{id}/answers` (the student's own saved picks, for resuming — never the
  answer key) ·
  `POST /quiz-attempts/{id}/submit` (always accepted, idempotent once terminal) ·
  `GET /quiz-attempts/{id}/result` (`409 QUIZ_ATTEMPT_NOT_SUBMITTED` while still `IN_PROGRESS`;
  `EXPIRED`/`SUBMITTED` are both allowed) — none of this group takes an `Idempotency-Key`;
  synchronous CRUD, no job/LLM call involved

**Planner** (Phase 7 — see [10-study-features.md](10-study-features.md); no `Idempotency-Key`,
synchronous CRUD, no LLM call — see `docs/DECISIONS.md` for why this deviates from
[01-architecture.md](01-architecture.md)'s async classification)
- `POST /documents/{id}/study-plans` `{examDate}` → `201` with sessions embedded
- `GET /documents/{id}/study-plans` · `GET /study-plans/{id}`
- `GET /study-plans/{id}/export.ics` → `text/calendar`

**Tutor** (Phase 2 — see [09-rag.md](09-rag.md) §Grounding contract)
- `POST /documents/{id}/conversations` → 201 `{id, documentId, createdAt}`
- `GET /documents/{id}/conversations` (cursor-paginated) · `GET /conversations/{id}`
- `GET /conversations/{id}/messages`
- `POST /conversations/{id}/messages` `{content, explainBeyondNotes}` → `200`, streamed
  `text/event-stream` (`token`/`done`/`error` events) — synchronous streaming, not an async job
  (see [01-architecture.md](01-architecture.md) sync/async boundary table), so **no**
  `Idempotency-Key`, unlike every other job-creating POST in this table.

**Jobs**
- `GET /jobs/{id}` · `GET /jobs` (for re-attaching in-flight jobs on page load)

**Ops**
- `GET /actuator/health` (public liveness)

Endpoints for exports, billing, and admin are in the original spec's endpoint map and will be
added to this file phase by phase — see `ROADMAP.md`.
