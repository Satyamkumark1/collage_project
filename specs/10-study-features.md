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

## Deferred (full spec exists, not built this phase)

- **Key points** — categorised concept/definition/formula/fact/date extraction with per-point
  source chunks, LaTeX-preserved formulas.
- **MCQs** — 10/25/50 counts, difficulty mix, Bloom-level mix, batch generation with explicit
  chunk-coverage steering, full §6.3 semantic validation (distinct options, valid correct_index,
  no lazy "all of the above").
- **Flashcards** — SM-2 spaced repetition (`ease_factor`, `interval_days`, `repetitions`,
  `due_at` computed in the user's timezone).
- **Quizzes** — server-authoritative timing, incremental answer saving, PRACTICE/EXAM/REVISION
  modes, negative marking, answer keys never sent to the client pre-submission.
- **Study planner** — exam-date-driven session scheduling, spaced revision insertion, `.ics`
  export.
- **Exports** — server-rendered PDF/DOCX with Unicode (Devanagari) font embedding.

Each of these gets its own detail added to this file (or a split-out file, if it grows large) when
its phase starts — see `ROADMAP.md`. The original spec's full behavioural detail for each
(word counts, SM-2 formulas, quiz scoring rules, planner constraints) is preserved in the pasted
master spec and should be pulled forward verbatim when that phase begins, not re-derived from
memory.
