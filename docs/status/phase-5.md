# Phase 5 — Infra hardening

**Status: ⬜ Not started**

## Scope (per [`specs/ROADMAP.md`](../../specs/ROADMAP.md))

Cloudinary (replaces the local-disk `StorageProvider` implementation from Phase 1), Upstash Redis
(L1+L2 rate limiting, SSE job streaming, job pub/sub — replacing Phase 1's polling-only job
progress and login-only rate limiting), Testcontainers (if Docker becomes available — replaces
the local-`studyflow_test`-Postgres deviation), full observability (Prometheus/Grafana/alerting/
runbook — see [`specs/13-observability-and-ops.md`](../../specs/13-observability-and-ops.md)),
DOCX/PPTX parsing (Apache POI).

## Why it hasn't started

Deferred intentionally — every piece of this phase exists to swap in a "real" version of
something Phase 1 built an interface for specifically so this swap is additive, not a rewrite
(see the relevant entries in [`docs/DECISIONS.md`](../DECISIONS.md)).

## What it needs before starting

- **Cloudinary account** — not currently available.
- **Upstash Redis account**, or a decision to run Redis locally instead (the binary is present
  on this machine but not currently running).
- **Docker**, for Testcontainers — not currently installed on this machine.
- Nothing blocks DOCX/PPTX parsing specifically except sequencing — could be pulled forward
  independently of the rest of this phase if there's a reason to.
