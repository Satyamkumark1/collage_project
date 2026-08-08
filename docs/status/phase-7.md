# Phase 7 — Planner, exports, admin

**Status: ⬜ Not started**

## Scope (per [`specs/ROADMAP.md`](../../specs/ROADMAP.md))

Study planner (exam-date-driven session scheduling, spaced revision insertion, `.ics` export),
server-rendered PDF/DOCX exports with Devanagari font embedding (Unicode support for Hindi/Indian
script notes), an admin read-only panel.

## Why it hasn't started

Last phase in the sequence — depends on the study features (Phases 3-4) that a planner would
schedule and an export would render.

## What it needs before starting

- Nothing blocked on external accounts.
- Devanagari font embedding for PDF/DOCX export needs a specific font-handling library decision
  not yet made.

## Ongoing / cross-cutting work (not tied to a single phase)

Per `specs/ROADMAP.md`, these get folded into whichever phase they're nearest to, rather than
tracked as their own phase: real email delivery (verify/reset flow, replacing Phase 1's
auto-verify deviation), DPDP guardian-consent collection UX (replacing Phase 1's structural-block-
only gate), `/me/delete` and `/me/export` (DPDP erasure/portability rights), full security
hardening from [`specs/14-security-privacy-compliance.md`](../../specs/14-security-privacy-compliance.md)
(zip-bomb/page-count caps, SSRF review once URL-accepting features exist), and whatever
`specs/15-PENDING.md`'s §13–§17 turn out to cover once the rest of the master spec is provided.
