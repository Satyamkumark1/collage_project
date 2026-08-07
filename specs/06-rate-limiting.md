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

No Redis (see `/docs/DECISIONS.md`), so **only the L1 in-process bucket exists**, and only for
login attempts (per email) — the bucket most directly tied to a security property (credential
stuffing resistance) rather than a cost-control property. AI-job-creation and upload rate limits
are not yet enforced by a token bucket; `usage_counters`-based monthly quotas (see
[12-billing-and-quotas.md](12-billing-and-quotas.md)) provide the cost-control backstop instead.

Full L1+L2 rate limiting, the `RATE_LIMITED` error code, and the rest of the bucket table are
deferred to the phase where Redis is introduced — see `ROADMAP.md`.
