# Phase 2 — Tutor chat + retrieval

**Status: ✅ Done** (2026-08-08)

## Scope

Hybrid retrieval (vector + lexical + RRF fusion + neighbour-chunk expansion — see
[`specs/09-rag.md`](../../specs/09-rag.md); a separate rerank stage was judged not worth building
without an eval harness to measure it against, see `specs/ROADMAP.md`), the grounding contract
(confidence-floor refusal, mandatory citations, an "explain beyond my notes" toggle), streaming
SSE chat, and new `conversations`/`messages` tables.

This is the feature the product's core promise most directly depends on — "a tutor that answers
only from your uploaded notes, with citations" (see
[`specs/00-product-and-constraints.md`](../../specs/00-product-and-constraints.md)).

## What was built

The original master spec's §7 (retrieval detail) was never actually provided (see
[`specs/15-PENDING.md`](../../specs/15-PENDING.md)) — every retrieval parameter (top-k sizes,
RRF's `k=60`, the 0.35 confidence floor, the tutor model choice) was designed for this build and
recorded with rationale in [`docs/DECISIONS.md`](../DECISIONS.md), rather than pulled from spec
text.

| Area | What | Key files |
|---|---|---|
| Migration | `content_tsv` generated column + GIN index on `document_chunks`; `conversations`/`messages` tables; `usage_counters.period_ym` widened for daily quota keys | `V12__tutor_chat.sql`, `V13__tutor_chat.sql`, `V14__usage_counters_period.sql` |
| Hybrid retrieval | Vector (pgvector cosine, `ef_search=40`) + lexical (`ts_rank_cd`) arms, RRF fusion, neighbour-chunk expansion, published via `RetrievalService` | `rag/service/RetrievalService(Impl).java`, `rag/repo/ChunkEmbeddingDao.java`, `rag/repo/DocumentChunkRepository.java` |
| Streaming AI calls | `AiProvider.streamComplete` + `GroqAiProvider`'s SSE parser and a streaming-safe `<think>`-block filter (tag can split across chunks) | `ai/AiProvider.java`, `ai/groq/GroqAiProvider.java`, `ai/groq/ThinkBlockFilter.java` |
| Tutor chat | Conversation/message persistence, confidence-floor grounding decision, mechanical (not self-reported) citation extraction from `[n]` markers, SSE controller | `tutor/` package (domain, repo, dto, service, web) |
| Quota | `QuotaService` generalised for daily periods; `TUTOR_MESSAGES` metric, 30/day | `common/quota/QuotaService.java` |
| Tenancy | `ArchitectureTest` has 16 ArchUnit rules total, all tenancy checks across 8 owner-scoped repositories; Phase 1 had 5 owner-scoped repositories | `ArchitectureTest.java` |
| Frontend | Tutor page (chat thread, streamed replies via `fetch`+`ReadableStream`, explain-beyond-notes toggle, per-message grounding badge, citation rail), reachable from Document Detail | `frontend/src/pages/Tutor.tsx`, `frontend/src/api/tutor.ts`, `frontend/src/components/TutorCitations.tsx` |

## Evidence

Backend test suite against real infrastructure (real Postgres 15 + pgvector, real Groq streaming,
real Voyage AI embeddings):

```
cd backend && set -a && source .env && set +a && ./mvnw test
```

`ArchitectureTest` (16 ArchUnit rules total, all tenancy checks across 8 owner-scoped
repositories; Phase 1 had 5 owner-scoped repositories) and the new `GroqAiProviderStreamingTest` (SSE parsing +
think-block filtering against a fake server, including a `<think>` tag deliberately split across
two stream chunks) pass deterministically — no external dependency. The real-infrastructure
integration tests (`RetrievalServiceIntegrationTest`, `TutorChatIntegrationTest`, and Phase 1's
pre-existing `DocumentIngestionIntegrationTest`/`SummaryGenerationIntegrationTest`) each passed
cleanly when run in isolation with adequate spacing between runs, exercising: real Voyage
embeddings feeding hybrid retrieval (a semantic-paraphrase query and a rare-literal-phrase query
both find the right chunk); a real streamed Groq reply with mechanically-extracted, in-range
citations; the confidence-floor path producing `grounded: false` under the explain-beyond-notes
toggle; tenancy (`404` on another user's conversation); and the not-ready gate (`409` before
ingestion finishes).

**Known limitation, not a code defect:** this build's Voyage AI account has no payment method on
file and is hard-capped at 3 requests/minute (confirmed via direct API call — see
`docs/DECISIONS.md`). A single unbroken `mvn test` run across the whole suite fires more real
Voyage calls than that cap allows within the run's total wall-clock time, so it can intermittently
show a job landing in `FAILED` (`TRANSIENT_FAILURE`) instead of `SUCCEEDED` even though the job
engine's retry/backoff is working exactly as designed. Every `awaitJobTerminal` test helper was
updated to keep re-polling the dispatcher while it waits (mirroring the real `@Scheduled`
dispatcher, disabled in tests for determinism) so a requeued job still gets retried within the
test's own wait window — this fixes the retry path, not the underlying account-level rate cap.
Each test class passes reliably run on its own with normal spacing; adding a payment method to the
Voyage account (per Voyage's own guidance) would remove the ceiling entirely.

Frontend: `tsc -b` (strict mode) clean, `oxlint` clean (no new warnings), `npm run build` clean.

**End-to-end, driven through the real browser UI** (Playwright against the running dev servers) —
register → login → upload a real `.md` file → watched ingestion reach `READY` → opened the new
Tutor page → asked a question covered by the notes → watched a real, token-streamed Groq reply
arrive with an in-range `[2]` citation, a "Sourced from your notes" rail (page + section), and a
green "From your notes" badge → toggled "explain beyond my notes" on an unrelated question →
watched a real streamed reply with a "Beyond your notes" badge instead. No mocked step anywhere in
the path.

This walkthrough surfaced two real bugs, both fixed and covered by regression tests:

- **Duplicate citation rail.** `Tutor.tsx` rendered a citation list both per-message (from
  persisted data) and from a leftover `lastDone` state variable — visually duplicated the rail
  for the turn just sent. Removed the redundant `lastDone` rendering; citations now come from
  persisted message data only, same source for every turn.
- **Concurrent `/auth/refresh` returned `500` instead of `401 AUTH_REFRESH_REUSED`.** Found when a
  page reload's silent refresh raced another in-flight one; reproduced deterministically with two
  real concurrent `curl` calls sharing one cookie. Root cause was two stacked issues in
  `RefreshTokenService`: a same-class call to `revokeFamily` bypassed the Spring AOP proxy
  entirely (so its `@Transactional(REQUIRES_NEW)` was inert — fixed with the same self-injection
  pattern `JobLifecycleService` already uses), and `rotate()`/`AuthService.refresh()`'s
  `noRollbackFor` — now unnecessary and actively harmful once revocation persists independently —
  could have left an orphaned, unrevoked child token committed. Full writeup:
  `docs/DECISIONS.md`. Regression test:
  `AuthFlowIntegrationTest.concurrentRefreshWithTheSameCookieNeverProducesA500`. This is Phase 1
  auth code, not Phase 2 code, but it directly blocked verifying Phase 2's own feature and is a
  real correctness gap worth having fixed regardless of which phase touched it last.

Manual verification collection: [`docs/http/slice2.http`](../http/slice2.http).

## Explicitly not in this slice

A dedicated rerank stage after RRF fusion; the `TUTOR_OUT_OF_SCOPE` error code (reserved in the
spec but never given a definition beyond what the confidence-floor refusal already covers — see
`docs/DECISIONS.md`); cross-document tutor chat (one conversation is scoped to one document, same
as every other AI feature); conversation rename/delete/archive; Redis-backed L1+L2 rate limiting
for tutor messages (daily `usage_counters` quota is the backstop, same pattern as Phase 1's AI-job
quota). See [`phase-3.md`](phase-3.md) onward.
