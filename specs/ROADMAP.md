# Roadmap

Sequencing for building out the full Master Build Spec, phase by phase, per the spec's own §0
rule: one working vertical slice at a time, never shallow scaffolding across the whole surface.
`CLAUDE.md`'s Build Log tracks phase completion with exit-criteria evidence; this file tracks the
plan those phases follow.

## Phase 1 — Auth → Upload → Ingestion → Async Summary

**Status:** done, 2026-08-08.

The first fully-real vertical slice: a student can register, log in, upload a PDF, watch it get
parsed/chunked/embedded, request a summary, and receive a real Groq-generated, cited summary —
with no mocked step anywhere in the path. Full detail in the approved plan
(`/Users/cashify/.claude/plans/studyflow-ai-cheeky-globe.md`) and spread across
[00](00-product-and-constraints.md)–[09](09-rag.md) and [10](10-study-features.md) (summaries
only) of this folder.

Deviations locked in for this phase (full list + rationale in `/docs/DECISIONS.md`): local
Postgres instead of Testcontainers, local disk instead of Cloudinary, no Redis, no billing, auto
email-verification, DPDP gate without consent-collection UX, PDF/TXT/MD only, Voyage AI for
embeddings, no retrieval search yet (summaries are map-reduce, not RAG search).

## Phase 2 — Tutor chat + retrieval

**Status:** done, 2026-08-08.

Hybrid retrieval (vector + lexical + RRF + neighbour expansion — [09-rag.md](09-rag.md); rerank is
not implemented, see below), the grounding contract (confidence-floor refusal, mandatory
citations, "explain beyond my notes" toggle), streaming SSE chat, `conversations`/`messages`
tables. This is the feature the product's core promise ("only answers from your notes, with
citations") most directly depends on — it came right after the walking skeleton because everything
else (MCQs, flashcards, quizzes) is lower-risk engineering by comparison.

The original master spec's §7 (which would have specified retrieval parameters in detail) was
never actually provided (see [15-PENDING.md](15-PENDING.md)) — every parameter (top-k sizes, RRF's
`k=60`, the 0.35 confidence floor, the tutor model choice) was designed for this build rather than
pulled from spec text, and is recorded with rationale in `/docs/DECISIONS.md`. A dedicated rerank
stage (cross-encoder or similar, re-scoring the RRF-fused set before neighbour expansion) is
listed in the original spec but not implemented this phase — RRF fusion alone was judged
sufficient without an eval harness (Phase 3) to demonstrate rerank earns its added latency/cost;
revisit once that harness exists.

## Phase 3 — Batch study generation

Key points, MCQs (with the full §6.3 batch-validation + partial-success logic and chunk-coverage
steering), flashcards + SM-2. First phase where the "48 of 50 generated" partial-success UI
pattern and the eval harness (`eval/` directory, CI-gated quality gates) earn their cost — build
the eval harness alongside MCQs, not before there's a batch feature to evaluate.

## Phase 4 — Quizzes

**Status:** done, 2026-08-10.

Server-authoritative timing, incremental answer saving, PRACTICE/EXAM/REVISION modes, scoring,
result breakdowns, OMR-bubble UI motif (only makes sense once there are single-choice controls to
skin). Built as a thin wrapper around Phase 3's MCQ generation pipeline rather than a new
generation path — see `docs/DECISIONS.md` and `docs/status/phase-4.md` for the full design
(mode semantics, timing formula, negative marking) and verification detail. None of the quiz
behavioural detail came from the master spec either — same `15-PENDING.md` gap as Phase 2/3's
invented numbers.

## Phase 5 — Infra hardening

**Status:** in progress, second slice done 2026-08-11.

~~Cloudinary (replaces local disk `StorageProvider` impl)~~ — **dropped, user decision
2026-08-11: local disk storage is permanent.** Upstash Redis (L1+L2 rate limiting, SSE job
streaming, job pub/sub), Testcontainers (real Postgres in tests via Docker), full observability
(Prometheus/Grafana/alerting/runbook), DOCX/PPTX parsing.

DOCX/PPTX ingestion and the login-attempt L1 rate limiter landed first — the two tracks needing no
external accounts (see `docs/DECISIONS.md`). An Upstash account and Docker are now available
(user decision 2026-08-11); Redis's durable L2 login-lock slice and Testcontainers (replacing the
local-`studyflow_test` deviation) have both landed — see `docs/DECISIONS.md`. SSE job streaming
and job pub/sub are next, not blocked on anything anymore. Observability still has no stack
decision made.

## Phase 6 — Billing

**Status:** dropped, 2026-08-11 — user decision, no Razorpay integration. No `plans`/
`subscriptions` tables, no plan-tiered quotas/job priority. See `docs/DECISIONS.md`.

## Phase 7 — Planner, exports, admin

**Status:** in progress, study planner done 2026-08-11 (see `docs/DECISIONS.md`) — started ahead
of Phase 5/6's remaining tracks since those are blocked on external credentials and this isn't.

Study planner (spaced revision scheduling, `.ics` export), server-rendered PDF/DOCX exports with
Devanagari font support, admin read-only panel.

## Ongoing / cross-cutting, not a discrete phase

Real email delivery (verify/reset flow), DPDP guardian-consent collection UX, `/me/delete` and
`/me/export` (DPDP erasure/portability), full security hardening from
[14-security-privacy-compliance.md](14-security-privacy-compliance.md) (zip-bomb/page-count caps,
SSRF review once URL-accepting features exist), and whatever §13–§17 turn out to cover once
provided (see [15-PENDING.md](15-PENDING.md)) — each gets folded into the nearest relevant phase
above rather than tracked as its own phase.
