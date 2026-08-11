# Phase 7 — Planner, exports, admin

**Status: 🟡 In progress** — study planner done, exports/admin not started.

## Scope (per [`specs/ROADMAP.md`](../../specs/ROADMAP.md))

Study planner (exam-date-driven session scheduling, spaced revision insertion, `.ics` export),
server-rendered PDF/DOCX exports with Devanagari font embedding (Unicode support for Hindi/Indian
script notes), an admin read-only panel.

## What landed — Study planner (2026-08-11)

Started ahead of finishing Phase 5/6 because those remaining tracks are all blocked on external
credentials (Cloudinary, Redis, Testcontainers, Razorpay) and the planner isn't — see
`docs/DECISIONS.md` for the full writeup, including why plan build is synchronous rather than the
async job the architecture table originally implied.

- Migration `V21__study_plans.sql`: `study_plans` (document, owner, exam date), `study_sessions`
  (per-plan scheduled dates). Both insert-only.
- `StudySessionScheduler` — pure function, fixed spaced-repetition-style cadence
  (`{21,14,10,7,5,3,2,1,0}` days before the exam, filtered to the actual window).
- `StudyPlanService`/`StudyPlanController` — `POST /documents/{id}/study-plans`,
  `GET /documents/{id}/study-plans`, `GET /study-plans/{id}`,
  `GET /study-plans/{id}/export.ics` (hand-written RFC 5545, no new dependency).
- `ArchitectureTest` extended to 32/32 (28 existing + 4 new for
  `StudyPlanRepository`/`StudySessionRepository`).
- Frontend: `StudyPlanner.tsx` (native `<input type="date">`, no picker library) at
  `/documents/:id/planner`, nav-linked from Document Detail.

Redacted verification — real Postgres, **no Groq/Voyage** (first study-feature test with zero
real-API dependency, so no rate-limit fragility):

```text
cd backend && set -a && source .env && set +a \
  && ./mvnw -q -Dtest=StudySessionSchedulerTest,ArchitectureTest,StudyPlanIntegrationTest test
...
Tests run: 5, Failures: 0 -- StudySessionSchedulerTest
Tests run: 32, Failures: 0 -- ArchitectureTest
Tests run: 2, Failures: 0 -- StudyPlanIntegrationTest
```

Frontend: `tsc -b` strict-mode clean, `oxlint` clean (no new warnings), `npm run build` clean.

## What's next

- **Exports** (PDF/DOCX, Devanagari font embedding) — needs a font-handling library decision, not
  yet made.
- **Admin read-only panel** — not started.

## Ongoing / cross-cutting work (not tied to a single phase)

Per `specs/ROADMAP.md`, these get folded into whichever phase they're nearest to, rather than
tracked as their own phase: real email delivery (verify/reset flow, replacing Phase 1's
auto-verify deviation), DPDP guardian-consent collection UX (replacing Phase 1's structural-block-
only gate), `/me/delete` and `/me/export` (DPDP erasure/portability rights), full security
hardening from [`specs/14-security-privacy-compliance.md`](../../specs/14-security-privacy-compliance.md)
(zip-bomb/page-count caps, SSRF review once URL-accepting features exist), and whatever
`specs/15-PENDING.md`'s §13–§17 turn out to cover once the rest of the master spec is provided.
