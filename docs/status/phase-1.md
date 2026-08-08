# Phase 1 — Auth → Upload → Ingestion → Async Summary

**Status: ✅ Done** (2026-08-08)

## Scope

The first fully-real vertical slice: a student registers, logs in, uploads a PDF/TXT/MD file,
watches it get parsed/chunked/embedded, requests a summary, and receives a real Groq-generated,
cited summary — with no mocked step anywhere in the path. Full detail in
[`specs/ROADMAP.md`](../../specs/ROADMAP.md) and spread across `specs/00`–`specs/10`.

## What was built, checkpoint by checkpoint

| # | Checkpoint | Commit |
|---|---|---|
| 1 | Users table (V1 migration) | `060aa2b` |
| 2 | RFC 9457 error model + `X-Request-Id` filter | `060aa2b` |
| 3 | Auth: register/login/refresh/logout, JWT + refresh rotation with reuse detection (V2) | `060aa2b` |
| 4 | DPDP age gate (structural block on AI-feature endpoints) | `060aa2b` |
| 5 | Job engine core: claim/dispatch/heartbeat/sweep (V3) | `db141f0` |
| 6 | Storage + upload: magic-byte sniffing, sha256 dedup, quotas (V4, V5) | `e938657` |
| 7 | Ingestion pipeline: parse/normalize/chunk (V6) | `a61b4f7` |
| 8 | Real Voyage AI embeddings, `vector(1024)` (V7) | `a61b4f7` |
| 9 | Groq AI provider adapter, prompt registry, `ai_calls` ledger (V8) | `47ca568` |
| 10 | Real cited summary generation with structured-output repair loop (V9) | `47ca568` |
| 11 | ArchUnit tenancy test (5 owner-scoped repositories) | `6288aae` |
| 12 | Frontend: Vite + React 19 + TypeScript strict, TanStack Query, design tokens | `80ee948` |
| 13 | UI states (loading/empty/error/success) + accessibility pass | `80ee948` |
| 14 | Build Log evidence + `docs/http/slice1.http` | `931421b` |

## Deviations locked in for this phase

Full list with dates and rationale in [`docs/DECISIONS.md`](../DECISIONS.md): local Postgres
instead of Testcontainers, local disk instead of Cloudinary, no Redis, no billing, auto
email-verification, DPDP gate without consent-collection UX, PDF/TXT/MD only, Voyage AI for
embeddings (`voyage-4-lite`, dimension 1024 confirmed via a real live API call), no retrieval
search yet (summaries are map-reduce over a document's own chunks, not RAG search). Plus several
found during the build itself: Spring Boot 4.0.7 (not 3.x — the scaffold already targeted 4.0's
new starter names), pgvector built from source (Homebrew's bottle doesn't target this machine's
Postgres 15), a citext/Hibernate binding trap, a JPA-flush-ordering bug between Hibernate-managed
and raw-JDBC writes in the same transaction.

## Evidence

Full backend test suite green against real infrastructure — real Postgres 15 + pgvector, real
Groq, real Voyage AI (no mocks anywhere in the integration tests):

```
cd backend && set -a && source .env && set +a && ./mvnw test
# 20 tests, 0 failures — includes ArchitectureTest's 5 tenancy rules and 3 integration
# tests that make real external API calls (ingestion, embeddings, summary generation)
```

Frontend: `tsc -b` (strict mode) clean, `npm run build` clean.

**End-to-end, driven through the real browser** (Playwright against the running dev servers, not
just curl): register → login → upload a real `.md` file → watched ingestion reach `READY` →
clicked *Generate summary* → watched a real Groq-generated (`openai/gpt-oss-120b`), cited summary
appear with a working "Sourced from your notes" citation rail, citations pointing at real
`document_chunks` rows → confirmed the document shows *Ready* back on the library list. Also
verified: full keyboard tab order with visible focus rings on every control, the login error
banner (uniform "Incorrect email or password" copy, matching the backend's anti-enumeration
design), native HTML5 password-length validation.

Manual verification collection for hand-testing: [`docs/http/slice1.http`](../http/slice1.http).

## Explicitly not in this slice

MCQs, flashcards, quizzes, tutor chat, study planner, exports, billing/Razorpay, admin panel;
DOCX/PPTX parsing; hybrid retrieval (chunk embeddings are stored but not yet queried); Redis;
Cloudinary; real email delivery; guardian-consent collection UX; the eval harness; observability
stack; Testcontainers. See [`phase-2.md`](phase-2.md) onward.
