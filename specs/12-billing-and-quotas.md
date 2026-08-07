# Billing & Quotas

## Target design (spec)

Razorpay Subscriptions, INR, GST-inclusive display. Plans and limits live in `plans.limits`
(jsonb), read at runtime. Quota check at enqueue time, inside the same transaction that creates
the job, atomic upsert on `usage_counters`. Webhook handler verifies HMAC signature (timing-safe),
idempotent via `razorpay_event_id` unique constraint, processes async, returns `200` fast. Never
grant entitlement from a client-side payment callback — only the verified webhook changes
`subscriptions.status`. `PAST_DUE` keeps read access, blocks new AI jobs for 3 days before
downgrade. Downgrade never deletes content.

## This phase — deferred entirely

No Razorpay account is wired in, no `plans`/`subscriptions` tables exist yet (see
[02-data-model.md](02-data-model.md)). What **is** real this phase:

- `usage_counters` exists and is enforced — a single hardcoded limit set in
  `application.yml` (e.g. uploads/month, AI jobs/month) stands in for plan-tiered limits.
- The quota check happens at the same point in the code the spec describes (enqueue time,
  atomic `INSERT ... ON CONFLICT DO UPDATE`), so introducing real plans later changes where the
  limit number comes from, not the enforcement mechanism.
- `QUOTA_UPLOADS_EXCEEDED` / `QUOTA_AI_EXCEEDED` (see
  [03-api-and-errors.md](03-api-and-errors.md)) are real, returned codes — not stubs.

Razorpay integration, the `plans`/`subscriptions` tables, and priority-by-plan in the job queue
(see [07-jobs-and-async.md](07-jobs-and-async.md)) land together in a later phase — see
`ROADMAP.md`.
