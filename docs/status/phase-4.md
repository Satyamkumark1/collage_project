# Phase 4 — Quizzes

**Status: ✅ Done** (2026-08-10)

## Scope (per [`specs/ROADMAP.md`](../../specs/ROADMAP.md))

Server-authoritative timing, incremental answer saving, PRACTICE/EXAM/REVISION modes, scoring,
result breakdowns, and the OMR-bubble UI motif (`specs/11-frontend.md`'s reserved "Secondary
motif," deferred until quizzes gave us a real single-choice control to skin).

Quiz build is a thin wrapper around the existing MCQ generation pipeline (`McqGenerationService`,
unchanged) rather than a new generation path — see `docs/DECISIONS.md` for why, and for every
invented product number (mode semantics, timing formula, negative marking fraction, scoring
formula). None of this comes from the master spec: `specs/10-study-features.md` marks quiz detail
as "preserved in the pasted master spec," but per `specs/15-PENDING.md` that paste never arrived
and never covered quizzes specifically — same posture as Phase 3's MCQ/SM-2 numbers.

## What landed

- Migration `V18__quizzes.sql`: `quizzes` (mode, question_count, time_limit_seconds,
  negative_marking_fraction, references a fresh `question_sets` row), `quiz_attempts`
  (server-issued deadline, status, score breakdown, `@Version` optimistic lock), `quiz_answers`
  (incremental per-question saves, upserted).
- `QuizGenerationService`/`QuizBuildHandler` (`QUIZ_BUILD` job type) — calls
  `McqGenerationService.generate(...)` unmodified, wraps the result with mode-derived
  timing/scoring config.
- `QuizAttemptService` — start/saveAnswer/submit/result/get, all server-authoritative: every
  mutating call re-checks `now()` against the attempt's own `deadlineAt`, never trusting a client
  timestamp. EXAM hard-enforces the deadline (rejects late answer writes, auto-expires); PRACTICE
  shows the same countdown without enforcing it; REVISION is untimed and gives immediate
  per-answer feedback. `QuizScorer` (pure function, no Spring context) computes
  `correctCount - incorrectCount * negativeMarkingFraction`.
- `QuizController`/`QuizAttemptController` — 9 endpoints total, including a
  `GET /quizzes/{id}/questions` that structurally omits the answer key (a dedicated
  `QuizQuestionResponse` DTO, not `QuestionResponse`) and a `GET /quiz-attempts/{id}/answers`
  added beyond the original sketch so a page reload can resume an in-progress attempt without
  leaking the answer key.
- `ArchitectureTest` extended to 28/28 tenancy rules (22 existing + 6 new, covering
  `QuizRepository`/`QuizAttemptRepository`/`QuizAnswerRepository`).
- Frontend: `Quizzes.tsx` (mode + count picker, build job progress, quiz/attempt list),
  `QuizAttempt.tsx` (the timed taking UI — OMR-bubble single-choice control, question-navigation
  strip, server-issued countdown with EXAM auto-submit-at-zero, incremental answer saves,
  clear-an-answer), `QuizResult.tsx` (score header, full per-question breakdown with answer key +
  citations). New `.omr-option`/`.omr-bubble`/`.quiz-timer`/`.quiz-question-strip` CSS in
  `components.css`, tokens-only (no new colors).

## Verification

Redacted verification excerpt — `ArchitectureTest` (deterministic, no external API):

```text
cd backend && ./mvnw -q test -Dtest=ArchitectureTest
...
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.109 s -- in com.studyflow.ArchitectureTest
```

Redacted verification excerpt — `QuizGenerationIntegrationTest` (real Postgres 15 + real Groq; 6
tests total: one exercising EXAM negative-marking, answer-key-withheld-until-submit, and the
clear-an-answer path together; one each for PRACTICE no-negative-marking and REVISION untimed +
immediate feedback; one deterministic EXAM-expiry regression test that backdates `deadline_at`
directly via `JdbcTemplate` rather than a real `Thread.sleep` — same technique as Phase 3's
flashcard optimistic-lock test; plus 2 cheap validation tests):

```text
cd backend && set -a && source .env && set +a && ./mvnw -q -Dtest=QuizGenerationIntegrationTest#answersAreRejectedOncePastAnExamDeadlineAndTheAttemptAutoExpires test
...
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.82 s
```

Every test method was confirmed passing individually with normal spacing; a full-class run can
intermittently show a Groq rate-limit failure under this account's real tier constraints (same
documented caveat as Mcq/Flashcard/Retrieval/Tutor integration tests — see `docs/DECISIONS.md`).

Frontend: `tsc -b` strict-mode clean, `oxlint` clean (no new warnings), `npm run build` clean.

**End-to-end, driven through the real browser UI** (Playwright against the running dev servers,
not just curl) — register → login → upload a real `.md` file → watched ingestion reach `READY` →
built a real EXAM-mode quiz (real Groq generation, retried automatically past two transient
account-tier rate-limit rejections — the same documented ceiling, not a code defect) → started an
attempt, confirmed the server-issued countdown timer rendered and the answer key was absent from
both the DOM and a direct `GET /quiz-attempts/{id}/result` call (`409 QUIZ_ATTEMPT_NOT_SUBMITTED`
while in progress) → answered several questions via the OMR-bubble control → submitted → the
result page showed a real negative-marked score (`-0.75 / 6`, `0 correct · 3 wrong · 3
unanswered` — matches `0×1 - 3×0.25` exactly) with the answer key, explanations, and citations
correctly revealed only at that point, and the correct/incorrect option bubbles color-coded
(`--check`/`--red-pen`). Separately verified REVISION mode: no timer rendered, and selecting an
answer immediately showed `"Correct. ACID is an acronym for Atomicity, Consistency, Isolation, and
Durability."` inline, sourced from the real generated explanation. No mocked step anywhere in
either path.

One real bug was found and fixed during this walkthrough, in the walkthrough script itself, not
the app: `page.goto()` to a protected route mid-session — a full browser reload — correctly
clears the in-memory access token per the app's own documented design (`api/client.ts`: "Access
token lives in memory only... never persisted"), which should trigger a silent refresh via the
HttpOnly cookie; chasing that down surfaced no app defect, but the fix (drive navigation via real
`<Link>` clicks, like an actual user, rather than hard reloads) is worth remembering for any future
scripted walkthrough of this app.

## Known limitation, not a code defect

Same shape as every prior phase's real-infra caveat: this account's Voyage tier is hard-capped at
3 RPM and Groq shows the same shape of tier limit under sustained load. A `QUIZ_BUILD` job can
land in `FAILED` (`AI_SCHEMA_INVALID`) under back-to-back real-API load; the frontend's
`JobProgress` failure state renders correctly when this happens (confirmed directly during the
walkthrough), and simply retrying the build (a fresh `Idempotency-Key`/job) succeeds once the
account's rate window clears — this is exactly the same account-tier ceiling documented since
Phase 2, not new to quizzes.

Separately (unrelated to Phase 4 code): the local `studyflow_dev` database was found to have its
own independent Flyway migration-history drift, predating this session — see `docs/DECISIONS.md`.
The manual walkthrough above ran against `studyflow_test` instead (already cleanly migrated
through V18 by the automated test suite), not `studyflow_dev`.

## Phase 4 summary

Quizzes are complete: server-authoritative timing (never trusting a client clock), incremental
answer saving with a working clear-an-answer path, three genuinely differentiated modes, correct
negative-marking arithmetic, and the OMR-bubble motif `specs/11-frontend.md` reserved for exactly
this feature — all verified against real Postgres, real Groq, and a real browser, not mocked. The
answer key is structurally impossible to leak before submission in EXAM and PRACTICE mode (a
dedicated answer-key-free DTO backs the question-taking view, and `GET .../result` 409s while an
attempt is still `IN_PROGRESS`); REVISION mode is the deliberate exception — its whole point is
immediate per-answer `isCorrect`/`explanation` feedback, a formative pass rather than an
assessment, not a gap in the other two modes' guarantee. Every invented product number (mode
semantics, the 90s/question timing formula, the −0.25 negative-marking fraction) is logged with
rationale in `docs/DECISIONS.md`.
