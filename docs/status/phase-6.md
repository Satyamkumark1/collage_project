# Phase 6 — Billing

**Status: ⬜ Not started**

## Scope (per [`specs/ROADMAP.md`](../../specs/ROADMAP.md) and [`specs/12-billing-and-quotas.md`](../../specs/12-billing-and-quotas.md))

Razorpay subscriptions, `plans`/`subscriptions` tables, plan-tiered quotas and job priority,
webhook handling, a usage dashboard.

## Why it hasn't started

No Razorpay account for this build yet, and billing has no dependents in Phase 1's (or Phases
2-5's) feature set — there's nothing to monetize until the core product features exist.

## What it needs before starting

- **Razorpay account** — not currently available.
- The `usage_counters` enforcement mechanism this phase would build on top of is already real
  (Phase 1's atomic `INSERT ... ON CONFLICT DO UPDATE` at enqueue time) — introducing real plans
  later changes where the limit number comes from, not the enforcement code path itself. See the
  relevant entry in [`docs/DECISIONS.md`](../DECISIONS.md).
