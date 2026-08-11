# Rate Limiting

## Target design (spec)

Two tiers: L1 an in-process bucket per instance (cheap, absorbs bursts), L2 a Redis sliding
window (correct, shared across instances). Check L1 first; only touch Redis when L1 passes.

| Bucket | Limit |
|---|---|
| Login attempts per email | 5 / 15 min, then exponential lockout |
| Login attempts per IP | 30 / 15 min |
| Password reset per email | 3 / hour |
| Global API per user | 300 / hour |
| AI job creation, FREE | 15 / hour, 40 / day |
| AI job creation, PRO | 120 / hour |
| Tutor messages, FREE | 30 / day |
| Uploads | 20 / hour |

Always return `429` with `Retry-After` and `retryAfterSeconds` in the body (`RATE_LIMITED` code).

## This phase — deviation

Only login attempts (per email) have a real bucket — the one most directly tied to a security
property (credential stuffing resistance) rather than a cost-control property. AI-job-creation and
upload rate limits are not yet enforced by a token bucket; `usage_counters`-based monthly quotas
(see [12-billing-and-quotas.md](12-billing-and-quotas.md)) provide the cost-control backstop
instead.

Login's own L1/L2 split (added 2026-08-11, see `/docs/DECISIONS.md`) isn't the sliding-window
bucket this file describes above — L2 here durably stores only the established *lock* (via
Upstash's REST API, not a TCP client/sliding-window structure), not a shared request-rate window
across instances. Full L1+L2 sliding-window rate limiting for the rest of the bucket table (AI-job
creation, uploads) is still deferred — Cloudinary and Razorpay are permanently out (user decision,
not "revisit when an account exists"), but Redis itself is now available; this is a "hasn't been
built yet" gap, not a "blocked on credentials" one.
