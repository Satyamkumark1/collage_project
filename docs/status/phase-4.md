# Phase 4 — Quizzes

**Status: ⬜ Not started**

## Scope (per [`specs/ROADMAP.md`](../../specs/ROADMAP.md))

Server-authoritative timing, incremental answer saving, PRACTICE/EXAM/REVISION modes, negative
marking, scoring, result breakdowns, answer keys never sent to the client pre-submission. The
OMR-bubble UI motif only makes sense once there are single-choice controls to skin, which is why
it's sequenced after MCQs exist (Phase 3).

## Why it hasn't started

Depends on Phase 3's MCQ generation existing first — quizzes are built *from* generated question
sets.

## What it needs before starting

- Server-authoritative timing is a new pattern (client can't be trusted with the clock) — no
  precedent for this yet in the codebase.
- Negative-marking and scoring rules need to come from the original master spec's §8 detail (not
  yet condensed into `specs/10-study-features.md` in full).
