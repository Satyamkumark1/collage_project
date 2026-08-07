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
| `QUOTA_AI_EXCEEDED` | 402 | Monthly AI job cap hit |
| `VALIDATION_FAILED` | 400 | Request body failed bean validation |

Codes reserved for later phases (`AUTH_EMAIL_UNVERIFIED`, `RATE_LIMITED`,
`PAYMENT_SIGNATURE_INVALID`, `TUTOR_OUT_OF_SCOPE`, etc.) are listed in the original spec and will
be added to this table when the features that raise them are built.

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

**Jobs**
- `GET /jobs/{id}` · `GET /jobs` (for re-attaching in-flight jobs on page load)

**Ops**
- `GET /actuator/health` (public liveness)

Endpoints for MCQs, flashcards, quizzes, tutor, planner, exports, billing, and admin are in the
original spec's endpoint map and will be added to this file phase by phase — see `ROADMAP.md`.
