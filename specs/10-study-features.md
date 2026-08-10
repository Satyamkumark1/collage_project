# Study Features

## Summaries — this phase (QUICK type only)

Quick: 60–100 words, one paragraph, no headings. (Detailed and section-wise summaries are
deferred — same table, `summary_type` values `DETAILED`/`SECTIONWISE` added when built.)

Generation: map-reduce over the document's `document_chunks` in `chunk_index` order (not a
retrieval search — see [09-rag.md](09-rag.md)). If total chunk tokens exceed the input cap,
summarize in groups ("map") then combine ("reduce") rather than silently truncating — a summary
that quietly omits the back half of a document is worse than an error (see
[08-ai-layer.md](08-ai-layer.md) §Cost, budget, degradation).

Every summary carries `citations` — chunk references validated (at generation time, via the
repair loop) to actually belong to the source document. Regeneration creates a new `summaries`
row — never overwrites; comparing versions is a later UI concern.

## Key points, MCQs, flashcards — Phase 3

Built: categorised key-point extraction (concept/definition/formula/fact/date, per-point source
chunks, LaTeX-preserved formulas), MCQs (10/25/50 counts, difficulty mix, Bloom-level pairing,
chunk-coverage steering, semantic validation — distinct options, valid `correct_index`, no lazy
"all of the above"), flashcards with SM-2 spaced repetition. None of this came from the master
spec's actual §6.3/SM-2 detail — that paste never arrived (see
[15-PENDING.md](15-PENDING.md)) — every number here (difficulty mix, Bloom pairing, SM-2
constants) was designed fresh under real constraints and is logged with rationale in
`/docs/DECISIONS.md`; full checkpoint-by-checkpoint detail in
[`/docs/status/phase-3.md`](../docs/status/phase-3.md).

## Quizzes — Phase 4

Built as a thin wrapper around MCQ generation, not a new generation pipeline: `POST
/documents/{id}/quizzes` (`{mode, requestedCount}`) reuses the exact MCQ pipeline above to produce
a fresh `question_sets`/`questions` batch, then a `quizzes` row adds mode-derived timing/scoring
config on top (see [02-data-model.md](02-data-model.md)). Server-authoritative timing — every
mutating call on an attempt re-checks `now()` against the attempt's own server-issued
`deadline_at`, never a client-supplied timestamp.

Three modes:

- **EXAM** — hard deadline (`time_limit_seconds = questionCount × 90`, ~JEE/NEET MCQ pacing);
  answer writes are rejected once it passes and the attempt auto-finalizes as `EXPIRED`. Negative
  marking: `-0.25`/wrong, `+1`/correct, `0`/unanswered (JEE/NEET convention). Answer key withheld
  until submit.
- **PRACTICE** — same countdown shown, not enforced (writes/submit keep working past it, an
  "overtime" indicator instead of a hard stop). No negative marking. Same answer-key withholding.
- **REVISION** — untimed. No negative marking. The one mode where each answer save returns
  `isCorrect`/`explanation` immediately — formative, not an assessment.

`score = correctCount - incorrectCount × negativeMarkingFraction`, `maxScore = questionCount`.
`submit` is always accepted and idempotent, even past an EXAM deadline — only mid-attempt answer
*writes* hard-reject on expiry. None of this timing/scoring detail came from the master spec
either (same `15-PENDING.md` gap) — fresh design calls, logged in `/docs/DECISIONS.md`; full
verification detail in [`/docs/status/phase-4.md`](../docs/status/phase-4.md).

## Deferred (full spec exists, not built yet)

- **Study planner** — exam-date-driven session scheduling, spaced revision insertion, `.ics`
  export.
- **Exports** — server-rendered PDF/DOCX with Unicode (Devanagari) font embedding.

Each of these gets its own detail added to this file (or a split-out file, if it grows large) when
its phase starts — see `ROADMAP.md`. The original spec's full behavioural detail for each
(planner constraints, export rendering rules) is preserved in the pasted master spec and should be
pulled forward verbatim when that phase begins, not re-derived from memory — assuming it ever
arrives; see [15-PENDING.md](15-PENDING.md).
