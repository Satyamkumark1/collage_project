# Observability & Operations

## Target design (spec)

Structured JSON logs with `requestId`, hashed `userId`, route, status, duration, MDC-propagated
into worker threads — never logging content, tokens, or keys. Micrometer → Prometheus metrics
(request latency, job queue depth/claim latency/failure rate, LLM latency/tokens/cost/schema-
failure rate, retrieval latency, Redis command count, HikariCP saturation). Alerting on queue
depth, job failure rate, schema-failure rate, p95 latency, DB pool saturation, daily LLM spend,
circuit-breaker state. A `RUNBOOK.md` with one page per alert. Neon PITR backups, restore
procedure tested once before launch.

## This phase

- `X-Request-Id` filter and structured request logging exist (see
  [04-identity-and-security.md](04-identity-and-security.md) and
  [03-api-and-errors.md](03-api-and-errors.md)) — the never-log-secrets rule is honored from the
  first log line, not retrofitted.
- Spring Actuator `/actuator/health` is exposed for liveness. Readiness (DB + AI provider
  reachability), Prometheus metrics export, alerting, and the runbook are **deferred** — they earn
  their cost once there's a deployed, always-on instance to monitor (see constraint #3 in
  [00-product-and-constraints.md](00-product-and-constraints.md)); this phase runs locally.
- Backups are not applicable yet (local Postgres, no production data).

Full observability build-out is a later phase — see `ROADMAP.md`.
