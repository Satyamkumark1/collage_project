# Phase 2 — Tutor chat + retrieval

**Status: ⬜ Not started**

## Scope (per [`specs/ROADMAP.md`](../../specs/ROADMAP.md))

Hybrid retrieval (vector + lexical + RRF fusion + rerank + neighbour-chunk expansion — see
[`specs/09-rag.md`](../../specs/09-rag.md)), the grounding contract (confidence-floor refusal,
mandatory citations, an "explain beyond my notes" toggle), streaming SSE chat, and new
`conversations`/`messages` tables.

This is the feature the product's core promise most directly depends on — "a tutor that answers
only from your uploaded notes, with citations" (see
[`specs/00-product-and-constraints.md`](../../specs/00-product-and-constraints.md)). It comes
right after Phase 1's walking skeleton because everything after it (MCQs, flashcards, quizzes) is
lower-risk engineering by comparison.

## Why it hasn't started

Phase 1 just finished. Chunk embeddings are already being generated and stored during ingestion
(Phase 1, checkpoint 8) specifically so this phase doesn't need a re-ingestion migration to start
querying them.

## What it needs before starting

- Nothing blocked on external accounts — Groq and Voyage are already wired up and working.
- Real design work on the grounding contract (what confidence floor triggers a refusal, exact
  citation format, how the "explain beyond my notes" toggle changes the system prompt).
- Streaming SSE is a new sync/async pattern not yet used anywhere in this codebase — see
  [`specs/01-architecture.md`](../../specs/01-architecture.md)'s topology table (`Tutor chat |
  Streaming SSE | First token < 2.5s`).
