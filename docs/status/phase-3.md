# Phase 3 — Batch study generation

**Status: 🟨 Checkpoint 15 complete; later checkpoints pending**

## Scope (per [`specs/ROADMAP.md`](../../specs/ROADMAP.md) and [`specs/10-study-features.md`](../../specs/10-study-features.md))

Key points (categorised concept/definition/formula/fact/date extraction with per-point source
chunks, LaTeX-preserved formulas), MCQs (10/25/50 counts, difficulty mix, Bloom-level mix, batch
generation with explicit chunk-coverage steering, full semantic validation — distinct options,
valid `correct_index`, no lazy "all of the above"), flashcards with SM-2 spaced repetition
(`ease_factor`, `interval_days`, `repetitions`, `due_at` computed in the user's timezone).

This is the first phase where the "48 of 50 generated" partial-success UI pattern and the eval
harness (`eval/` directory, CI-gated quality gates: schema pass rate, MCQ validity, citation
groundedness, job latency) earn their cost — the eval harness gets built alongside MCQs, not
before there's a batch feature to evaluate.

## What landed so far

Checkpoint 15 is now in place:

- `ChunkQueryService.ChunkView` exposes the richer citation fields (`chunkIndex`, `pageFrom`,
  `pageTo`, `sectionPath`) used by key points and later batch-study features.
- `BatchRepairLoop` exists as the shared partial-success repair primitive for batch AI outputs.
- Key points extraction is wired end-to-end: migration, handler, controller, repository, prompt,
  response DTOs, frontend page/API, and a live Postgres/Groq integration test.

Redacted verification excerpt:

```text
cd backend && set -a && source .env && set +a && ./mvnw -q -Dtest=KeyPointGenerationIntegrationTest test
...
KeyPointGenerationIntegrationTest  : Started KeyPointGenerationIntegrationTest
...
BUILD SUCCESS
```

## What remains

Later checkpoints for MCQs, the eval harness, and flashcards remain pending.

## What it needs before starting

- Phase 1's structured-output repair loop (call → parse → schema validate → semantic validate →
  one repair call → fail) already exists and generalizes directly to batch generation — the new
  work is the *partial-success* variant: keep the valid items from a batch, drop only the
  malformed ones, rather than failing the whole batch.
- SM-2's exact interval/ease-factor formulas are in the original master spec (not yet pulled into
  `specs/10-study-features.md` in full) — pull them forward verbatim when this phase starts, per
  that file's own note, rather than re-deriving from memory.
