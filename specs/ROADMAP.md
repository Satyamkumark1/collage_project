# Roadmap

Sequencing for building out the full Master Build Spec, phase by phase, per the spec's own §0
rule: one working vertical slice at a time, never shallow scaffolding across the whole surface.
`CLAUDE.md`'s Build Log tracks phase completion with exit-criteria evidence; this file tracks the
plan those phases follow.

## Phase 1 (current) — Auth → Upload → Ingestion → Async Summary

**Status:** in progress, started 2026-08-08.

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

## Phase 2 (next) — Tutor chat + retrieval

Hybrid retrieval (vector + lexical + RRF + rerank + neighbour expansion — [09-rag.md](09-rag.md)),
the grounding contract (confidence-floor refusal, mandatory citations, "explain beyond my notes"
toggle), streaming SSE chat, `conversations`/`messages` tables. This is the feature the product's
core promise ("only answers from your notes, with citations") most directly depends on — it comes
right after the walking skeleton because everything else (MCQs, flashcards, quizzes) is lower-risk
engineering by comparison.

## Phase 3 — Batch study generation

Key points, MCQs (with the full §6.3 batch-validation + partial-success logic and chunk-coverage
steering), flashcards + SM-2. First phase where the "48 of 50 generated" partial-success UI
pattern and the eval harness (`eval/` directory, CI-gated quality gates) earn their cost — build
the eval harness alongside MCQs, not before there's a batch feature to evaluate.

## Phase 4 — Quizzes

Server-authoritative timing, incremental answer saving, PRACTICE/EXAM/REVISION modes, scoring,
result breakdowns, OMR-bubble UI motif (only makes sense once there are single-choice controls to
skin).

## Phase 5 — Infra hardening

Cloudinary (replaces local disk `StorageProvider` impl), Upstash Redis (L1+L2 rate limiting, SSE
job streaming, job pub/sub), Testcontainers (if Docker becomes available — replaces the local-test-
DB deviation), full observability (Prometheus/Grafana/alerting/runbook), DOCX/PPTX parsing.

## Phase 6 — Billing

Razorpay subscriptions, `plans`/`subscriptions` tables, plan-tiered quotas and job priority,
webhook handling, usage dashboard.

## Phase 7 — Planner, exports, admin

Study planner (spaced revision scheduling, `.ics` export), server-rendered PDF/DOCX exports with
Devanagari font support, admin read-only panel.

## Ongoing / cross-cutting, not a discrete phase

Real email delivery (verify/reset flow), DPDP guardian-consent collection UX, `/me/delete` and
`/me/export` (DPDP erasure/portability), full security hardening from
[14-security-privacy-compliance.md](14-security-privacy-compliance.md) (zip-bomb/page-count caps,
SSRF review once URL-accepting features exist), and whatever §13–§17 turn out to cover once
provided (see [15-PENDING.md](15-PENDING.md)) — each gets folded into the nearest relevant phase
above rather than tracked as its own phase.
