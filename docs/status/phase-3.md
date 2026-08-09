# Phase 3 — Batch study generation

**Status: ✅ Done** (2026-08-09)

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

Checkpoint 15:

- `ChunkQueryService.ChunkView` exposes the richer citation fields (`chunkIndex`, `pageFrom`,
  `pageTo`, `sectionPath`) used by key points and later batch-study features.
- `BatchRepairLoop` exists as the shared partial-success repair primitive for batch AI outputs.
- Key points extraction is wired end-to-end: migration, handler, controller, repository, prompt,
  response DTOs, frontend page/API, and a live Postgres/Groq integration test.

Checkpoint 16:

- Batch MCQ generation reuses `BatchRepairLoop`: `question_sets` (`requested_count`,
  `generated_count`, `difficulty_mix`) + `questions` (stem, 4 options, `correct_index`,
  explanation, difficulty, Bloom level, citations), migration `V16__mcqs.sql`.
- Difficulty mix (40/40/20 EASY/MEDIUM/HARD), Bloom-level pairing, and chunk-coverage steering via
  an explicit per-question target list are all fresh design calls, logged in `docs/DECISIONS.md`.
- 10 validation rules beyond JSON-shape parsing (distinct/non-lazy options, valid `correct_index`,
  citation groundedness, enum adherence, etc.), with large documents handled by
  partition-and-concatenate across token-budget groups (no natural "reduce" step for a question
  set).
- `ArchitectureTest` now covers `QuestionSetRepository`/`QuestionRepository` — 20 tenancy rules,
  up from 16.
- Frontend: a count picker (10/25/50), the job-progress flow, a new `.partial-banner` component
  state for "N of M generated" (`specs/11-frontend.md`'s previously-unfilled "partial" state), and
  a client-side answer-reveal interaction per question.

Redacted verification excerpt (checkpoint 16):

```text
cd backend && set -a && source .env && set +a && ./mvnw -q -Dtest=McqGenerationIntegrationTest test
...
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 20.72 s -- in com.studyflow.study.McqGenerationIntegrationTest
BUILD SUCCESS
```

`ArchitectureTest` (20/20) and `GroqAiProviderStreamingTest` (2/2) pass deterministically.
Frontend: `tsc -b` strict-mode clean, `oxlint` clean (no new warnings), `npm run build` clean.

Checkpoint 17:

- `eval/` harness: 3 starter documents + answer keys (growing toward 15-20), a Java runner
  (`com.studyflow.eval.EvalHarnessRunner`, `@Tag("eval")`, excluded from the default `mvn test`
  run via a new `surefire.excludedGroups` POM property) that runs the real
  upload→ingest→key-points→MCQ→retrieval-probe pipeline per document and computes the 5 metrics
  named in `specs/08-ai-layer.md`.
- First real baseline run: `eval/results/baseline.md` — schema pass rate 100% (3/3), citation
  groundedness 100% structural / 100% lexical-heuristic (45/45), retrieval recall 100% (9/9), p95
  job latency 30.2s. MCQ validity came back 0/0 that run because Groq itself rate-limited every
  MCQ batch under the harness's back-to-back load — a real, documented account-tier constraint
  (`docs/DECISIONS.md`), not a validity failure; `McqGenerationIntegrationTest` (a single MCQ call)
  passes cleanly in isolation.
- CI wiring remains deferred to Phase 5, per the earlier decision.

Redacted verification excerpt (checkpoint 17):

```text
cd backend && set -a && source .env && set +a && ./mvnw test -Dtest=EvalHarnessRunner -Dsurefire.excludedGroups=
...
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 292.6 s -- in com.studyflow.eval.EvalHarnessRunner
BUILD SUCCESS
```

Checkpoint 18:

- Flashcard batch generation reuses `BatchRepairLoop` (migration `V17__flashcards.sql`,
  `FLASHCARD_GENERATE` job type): `flashcards` (`front_md`, `back_md`, citations, plus SM-2 state
  — `ease_factor`, `interval_days`, `repetitions`, `due_at`, `last_reviewed_at`, `last_quality`).
- SM-2 (Piotr Wozniak's original formulas), a pure `Sm2Calculator` with its own 10-case plain-JUnit
  test — the master spec's SM-2 formulas were never actually transcribed anywhere in this repo, so
  they're a fresh design call, logged in `docs/DECISIONS.md`, same posture as checkpoint 16's MCQ
  numbers.
- `flashcards` is the first mutable row in `study/` — carries `@Version` (JPA optimistic locking),
  verified with a deterministic test proving a stale concurrent review is rejected rather than
  silently overwriting a newer one.
- `POST /flashcards/{id}/review` is synchronous (pure SM-2 arithmetic, no LLM call, no
  Idempotency-Key) — `GET /flashcards/due` is a plain top-N-by-due-date query, not
  cursor-paginated (a due-now queue is inherently dynamic; see `docs/DECISIONS.md`).
- `ArchitectureTest` now covers `FlashcardRepository` — 22 tenancy rules, up from 20.
- Frontend: a flip-card review UI (front → "Show answer" → back + citations + 4 quality buttons
  collapsing SM-2's 0-5 scale, Anki-style), `--highlight`-keyed due-now badges on both the deck
  list and a library-wide nav-bar indicator (`tokens.css`'s `--highlight` was reserved for exactly
  this since Phase 2, previously unused).

Redacted verification excerpt (checkpoint 18):

```text
cd backend && set -a && source .env && set +a && ./mvnw -q test -Dtest=Sm2CalculatorTest,FlashcardGenerationIntegrationTest
...
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.028 s -- in com.studyflow.study.service.Sm2CalculatorTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 19.43 s -- in com.studyflow.study.FlashcardGenerationIntegrationTest
BUILD SUCCESS
```

`ArchitectureTest` (22/22) and `GroqAiProviderStreamingTest` (2/2) pass deterministically.
Frontend: `tsc -b` strict-mode clean, `oxlint` clean (no new warnings), `npm run build` clean.

## Known limitation, not a code defect

Running the full Phase 3 real-infra suite (`KeyPointGenerationIntegrationTest`,
`McqGenerationIntegrationTest`, `FlashcardGenerationIntegrationTest`) back-to-back in one `mvn
test` invocation can intermittently show a Voyage or Groq rate-limit failure — this account's
Voyage tier is hard-capped at 3 RPM, and Groq showed the same shape of tier limit under checkpoint
17's eval-harness load (see `docs/DECISIONS.md`). Every test class here passes cleanly run on its
own with normal spacing (confirmed this session for all three), matching the exact same caveat
already documented for Phase 2.

## Phase 3 summary

All four checkpoints (key points, MCQs, eval harness, flashcards+SM-2) are complete, each verified
against real Postgres 15 + real Groq + real Voyage AI, with every invented product number (RRF-
style: difficulty mix, Bloom pairing, chunk-coverage steering, SM-2 constants, eval thresholds)
logged with rationale in `docs/DECISIONS.md` rather than silently assumed. The "48 of 50 generated"
partial-success UI pattern and the `eval/` harness — the two things this phase was explicitly
sequenced to earn — both exist and were exercised against real generations, not mocked.
