# Phase 5 — Infra hardening

**Status: 🟡 In progress** — DOCX/PPTX parsing, L1+L2 login rate limiting, and Testcontainers
done; Cloudinary dropped; Redis's remaining tracks (SSE job streaming, pub/sub) and observability
not started.

## Scope (per [`specs/ROADMAP.md`](../../specs/ROADMAP.md))

Cloudinary (replaces the local-disk `StorageProvider` implementation from Phase 1), Upstash Redis
(L1+L2 rate limiting, SSE job streaming, job pub/sub — replacing Phase 1's polling-only job
progress and login-only rate limiting), Testcontainers (if Docker becomes available — replaces
the local-`studyflow_test`-Postgres deviation), full observability (Prometheus/Grafana/alerting/
runbook — see [`specs/13-observability-and-ops.md`](../../specs/13-observability-and-ops.md)),
DOCX/PPTX parsing (Apache POI).

## What landed

**Checkpoint A+B (2026-08-11)** — DOCX/PPTX ingestion (`DocxDocumentParser`/`PptxDocumentParser`,
migration `V20`) and the login-attempt L1 rate limiter (`LoginRateLimiter`, 5 failures/15 min,
exponential lockout) — the two tracks needing no external accounts. Full design in
`docs/DECISIONS.md`.

**Checkpoint C (2026-08-11)** — Redis L2 login-lock durability. Cloudinary and Razorpay are
permanently out (user decision — local disk storage stays, no billing phase), but an Upstash
account and Docker are now available. `RedisLoginLockStore` durably mirrors an established L1
lockout via Upstash's REST API (not a TCP client — one `SET .. EX`/`TTL` command doesn't justify
`spring-boot-starter-data-redis`), so a lock survives an app restart, which L1's in-memory map
alone can't. `LoginLockStore` interface keeps `LoginRateLimiterTest` a fast, network-free unit
test (a no-op stub); the real durability property is verified against real Upstash in
`LoginRateLimitIntegrationTest`. Full design and the "found along the way" bug (a hand-duplicated
`src/test/resources/application.yml` that silently doesn't pick up new main config keys) are in
`docs/DECISIONS.md`.

```text
cd backend && set -a && source .env && set +a \
  && ./mvnw test -Dtest=LoginRateLimiterTest,LoginRateLimitIntegrationTest,ArchitectureTest,AuthFlowIntegrationTest
```

`LoginRateLimiterTest` 5/5, `LoginRateLimitIntegrationTest` 3/3 (real Postgres + real Upstash),
`ArchitectureTest` 32/32 (unaffected), `AuthFlowIntegrationTest` 6/6 (no regression).

**Checkpoint D (2026-08-11)** — Testcontainers. Integration tests now run against a real, fresh
`pgvector/pgvector:pg15` container instead of the shared local Homebrew Postgres — one container
per JVM/surefire fork (a JUnit5 global extension, not per-test-class), so no existing test file
needed editing. Surfaced and fixed a real, previously-hidden bug along the way: migration V7 uses
the `vector` type before V10 creates the extension, invisible until a truly fresh database was
ever used. Full design in `docs/DECISIONS.md`.

```text
cd backend && set -a && source .env && set +a && ./mvnw test
```

101/109 passed in one full run; the 8 failures were the already-documented Groq/Voyage rate-limit
shape (7) plus one timing-sensitive `JobDispatcherIntegrationTest` flake under full-suite load
(confirmed 4/4 passing standalone immediately after — not a Testcontainers regression).

## What's left

- **SSE job-progress streaming + job pub/sub** — Redis is available now, not started yet.
- **Observability** — still needs a stack decision (self-hosted Prometheus/Grafana via Docker, or
  a hosted free tier).
- **Cloudinary** — dropped permanently, not "not started." Local disk storage is the permanent
  choice.
