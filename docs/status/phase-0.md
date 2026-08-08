# Phase 0 — Session 0

**Status: ✅ Done** (2026-08-08)

## Scope

Decompose the pasted Master Build Spec v2.0 into `specs/`, write `CLAUDE.md` (the operating
contract) and `docs/DECISIONS.md` (deviations log), and stand up empty backend + frontend
scaffolds that actually boot.

## What was built

- `specs/` — 17 files (`00` through `15-PENDING`, plus `README.md` and `ROADMAP.md`), each a
  condensed, reorganized rewrite of its slice of the master spec — not a verbatim copy, not a
  lossy summary.
- `CLAUDE.md` — tech stack, module boundaries, error model, naming conventions, hard "never do
  this" rules, the verification loop, and this Build Log.
- `docs/DECISIONS.md` — every deviation from the master spec, dated and justified, starting with
  the big ones locked in before any code: Testcontainers → local Postgres, Cloudinary → local
  disk, Upstash Redis → none, Razorpay → deferred, email verification → auto-verify, DPDP age gate
  → structural block only.
- `backend/` — Spring Boot scaffold. Found and fixed a real bug here: the initial `pom.xml` had
  `4.0.7.RELEASE` as the Spring Boot version, which doesn't resolve (modern Spring Boot dropped
  the `.RELEASE` suffix). Fixed to `4.0.7` — a real, current GA release, confirmed directly
  against Maven Central rather than assumed.
- `frontend/` — placeholder Vite app (fleshed out into the real app in Phase 1's checkpoints
  13-14).

## Evidence

- `specs/` contains all 17 files with real, actionable content (table shapes, column lists,
  thresholds, endpoint signatures preserved) — verified by reading each one before Phase 1 began.
- `mvn compile` clean against real Maven Central artifacts (no cached hallucinated coordinates).
- `curl http://localhost:8080/actuator/health` → `200 {"status":"UP"}`.
- `npm run build` clean (frontend placeholder at the time; full app came later).

## Commit

`4bb71bd` — Session 0: specs, decisions log, and backend scaffold (checkpoint 1).
