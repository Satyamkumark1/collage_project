# Jobs & Async Model

Table `ai_jobs` (see [02-data-model.md](02-data-model.md)). A worker pool claims work with
`SELECT ... FOR UPDATE SKIP LOCKED` — this is what makes it safe when running two instances later.

## States

`QUEUED → RUNNING → SUCCEEDED | FAILED | CANCELLED`. Terminal states are final.

## Claim query

```sql
UPDATE ai_jobs SET status = 'RUNNING', heartbeat_at = now(),
  started_at = coalesce(started_at, now()), attempts = attempts + 1
WHERE id = (
  SELECT id FROM ai_jobs
  WHERE status = 'QUEUED' AND run_after <= now()
  ORDER BY priority DESC, created_at ASC
  FOR UPDATE SKIP LOCKED
  LIMIT 1
)
RETURNING *;
```

## Worker pool

`JobDispatcher` — a `@Scheduled(fixedDelay = 1000)` poller claims a job and submits it to a
bounded `ExecutorService`. One `JobHandler` implementation per `task_type`
(`DocumentIngestHandler`, `SummaryGenerateHandler` this phase). A `ProgressReporter` passed into
the handler writes `progress_pct`/`progress_stage` to the row as work proceeds.

**Heartbeat.** While a job runs, its `heartbeat_at` is updated every 10s.

**Sweeper.** `JobSweeper` — `@Scheduled(fixedDelay = 30000)` requeues jobs stuck in `RUNNING`
whose `heartbeat_at` is older than 90s and whose `attempts < max_attempts`, back to `QUEUED` with
`run_after` pushed out by backoff.

**Retry.** Exponential backoff with jitter — 5s, 20s, 80s. Max 3 attempts. Retry only on
transient failure classes (429s, 5xxs, timeouts, connection resets from the AI/embedding
providers). Never retry on validation failure or quota exhaustion — those go straight to `FAILED`.

**Idempotency.** Client sends `Idempotency-Key` on job creation. Unique index on
`(owner_id, idempotency_key)`. A repeat within 24h returns the original job, not a new one.

**Dedupe.** `input_fingerprint = SHA-256(document_id | task_type | params | prompt_version)`. A
`SUCCEEDED` job with the same fingerprint returns the cached `result_ref` instead of re-running —
cuts LLM spend materially during repeated requests.

**Priority.** Column exists (`priority`, default 0) but isn't differentiated yet — billing-tiered
priority is deferred until plans exist (see [12-billing-and-quotas.md](12-billing-and-quotas.md)).

**Progress / cancellation.** This phase: client polls `GET /jobs/{id}` (no Redis pub/sub, no SSE
stream endpoint — see `/docs/DECISIONS.md`). `POST /jobs/{id}/cancel` and the SSE
`GET /jobs/{id}/stream` endpoint are deferred to the phase where Redis is introduced.

## Task types this phase

- `DOCUMENT_INGEST` — see [09-rag.md](09-rag.md) §Ingestion pipeline.
- `SUMMARY_GENERATE` — see [10-study-features.md](10-study-features.md) §Summaries.
