# StudyFlow AI — Specs

This folder decomposes the user's "StudyFlow AI — Master Build Spec v2.0" (pasted in full on
2026-08-08) into small, module-scoped files that mirror the backend's package structure. The
master spec describes the entire product; these files break it into pieces that can each be read
in one sitting and built against directly.

**Provenance.** Every file here is a condensed, reorganized rewrite of a slice of the original
spec — not a verbatim copy, and not a summary that drops the actionable detail (table shapes,
column lists, thresholds, endpoint signatures are preserved). Where the original spec text was
ambiguous or left an explicit decision to the builder (e.g. §7.3's embedding provider choice),
the decision made is recorded both here and in `/docs/DECISIONS.md`.

**Known gap.** The pasted spec truncated mid-§12 (Security/Privacy/Compliance), at "SSRF via…".
§12's remainder through §17 (rest of the threat model, JVM tuning/ops, budget, Non-Goals) were
never provided. See [`15-PENDING.md`](15-PENDING.md) — nothing in this folder invents content to
fill that gap.

## Index

| File | Covers (master spec §) |
|---|---|
| [00-product-and-constraints.md](00-product-and-constraints.md) | §1 Product definition, §2 Hard constraints the original brief got wrong |
| [01-architecture.md](01-architecture.md) | §3 Topology, sync/async boundary, package structure |
| [02-data-model.md](02-data-model.md) | §4 Full data model: tables, indexes, tenancy enforcement |
| [03-api-and-errors.md](03-api-and-errors.md) | §5.1–§5.3 API conventions, error model, endpoint map |
| [04-identity-and-security.md](04-identity-and-security.md) | Auth/token model, DPDP age gate, §5.6 security |
| [05-library-and-storage.md](05-library-and-storage.md) | §5.4 Upload flow, storage provider contract |
| [06-rate-limiting.md](06-rate-limiting.md) | §5.5 Rate limiting buckets |
| [07-jobs-and-async.md](07-jobs-and-async.md) | §3.2–§3.3 Async job model |
| [08-ai-layer.md](08-ai-layer.md) | §6 Provider abstraction, routing, structured output, prompts, eval harness |
| [09-rag.md](09-rag.md) | §7 Ingestion, chunking, retrieval, grounding contract |
| [10-study-features.md](10-study-features.md) | §8 Summaries, key points, MCQs, flashcards, quizzes, planner, exports |
| [11-frontend.md](11-frontend.md) | §9 Stack, "Answer Booklet" design system, pages |
| [12-billing-and-quotas.md](12-billing-and-quotas.md) | §10 Billing and quotas |
| [13-observability-and-ops.md](13-observability-and-ops.md) | §11 Observability and operations |
| [14-security-privacy-compliance.md](14-security-privacy-compliance.md) | §12 Security/privacy/compliance (**partial** — spec truncated) |
| [15-PENDING.md](15-PENDING.md) | §13–§17: not yet provided |
| [ROADMAP.md](ROADMAP.md) | Build phases, sequencing, current status |

## How this folder is used

`/CLAUDE.md`'s Build Log points at [`ROADMAP.md`](ROADMAP.md) for phase status. Each module file
is the reference for its area when implementing or reviewing that area — e.g. when touching the
job queue, read `07-jobs-and-async.md`, not the original 50,000-character paste.
