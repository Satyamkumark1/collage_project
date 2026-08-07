# Decisions Log

Every deviation from the master build spec, dated, with what changed, why, and what it costs.
Silent deviation is the failure mode; this log is what makes deviation legitimate instead.

---

## 2026-08-08 — Testcontainers → local Postgres for integration tests

**What changed:** Integration tests run against a real, dedicated local `studyflow_test`
database (Homebrew Postgres 15) instead of a Testcontainers-managed ephemeral Postgres.

**Why:** No Docker is installed in this environment (verified: `docker` command not found,
no daemon reachable). A local Postgres 15 server is already running and reachable.

**What it costs:** Tests are not hermetic across machines/CI the way Testcontainers would be —
`studyflow_test` must exist and be migrated before tests run, and test isolation between runs
depends on a `DatabaseCleaner` truncating tables rather than a fresh container per run. The spec's
actual intent (real Postgres, not mocks) is preserved. Revisit when Docker is available —
datasource config is structured so switching back is a config change, not a rewrite.

---

## 2026-08-08 — Cloudinary → local disk storage

**What changed:** File storage uses a `StorageProvider` interface with a `LocalDiskStorageProvider`
implementation writing to `STORAGE_LOCAL_ROOT`, instead of Cloudinary (`resource_type=raw`,
`type=authenticated`, signed URLs).

**Why:** No Cloudinary account/credentials available for this build.

**What it costs:** No CDN, no signed time-limited delivery URLs, no offloading of storage from the
app server. Upload is also a direct multipart `POST /documents` instead of the spec's presigned
upload-intent + direct-to-Cloudinary two-step (see next entry). Revisit when a Cloudinary account
exists — swapping in a `CloudinaryStorageProvider` is additive.

---

## 2026-08-08 — Presigned two-step upload → direct multipart upload

**What changed:** `POST /documents` accepts a direct multipart file upload and does parsing/
validation server-side, instead of `POST /documents/upload-intent` (signed params) + browser-to-
Cloudinary upload + `POST /documents` (confirm).

**Why:** The presigned two-step exists specifically to avoid proxying file bytes through the JVM
en route to a cloud CDN. With local disk storage (previous entry), that concern doesn't apply the
same way, and the two-step adds complexity with no corresponding benefit yet.

**What it costs:** Once Cloudinary is wired in, the upload endpoint contract changes and the
frontend upload flow needs rework. Magic-byte sniffing, size caps, and dedup-by-checksum are still
enforced server-side regardless of transport, so the security properties aren't weakened.

---

## 2026-08-08 — Upstash Redis → none (this phase)

**What changed:** No Redis dependency. Rate limiting is in-process (L1) only, and only for login
attempts. Job progress is read via polling `GET /jobs/{id}` directly from Postgres — no SSE
stream, no Redis pub/sub fan-out.

**Why:** No Upstash account for this build; Redis binary is present locally but not running, and
standing up local Redis for a single-instance dev build wasn't judged worth the operational
overhead yet.

**What it costs:** No shared rate-limit state across instances (irrelevant — single instance),
no L2 sliding-window correctness for AI-job-creation/upload buckets (monthly `usage_counters`
quotas are the backstop instead), no real-time job-progress push (polling is the spec's own
documented fallback path, not a lesser mode). Revisit in Phase 5 (see `specs/ROADMAP.md`).

---

## 2026-08-08 — Razorpay/billing → deferred entirely

**What changed:** No `plans`/`subscriptions` tables, no Razorpay integration. A single hardcoded
limit set in `application.yml` stands in for plan-tiered quotas.

**Why:** No Razorpay account for this build; billing has no dependents in Phase 1's feature set.

**What it costs:** No real monetization path yet, no plan-tiered job priority. The
`usage_counters` enforcement mechanism itself (atomic upsert at enqueue time) is still real, so
introducing real plans later changes where the limit number comes from, not the enforcement code
path. Revisit in Phase 6.

---

## 2026-08-08 — Email verification → auto-verify at registration

**What changed:** `email_verified_at` is set immediately at registration instead of through a
real send-a-link-and-click-it flow.

**Why:** No SMTP/email provider configured for this build.

**What it costs:** No actual proof of email ownership — a user could register with an email they
don't control. Acceptable for a local dev build with no real user data; **must** be fixed before
any real deployment. The column and any future gate logic already exist, so wiring real
verification later is additive.

---

## 2026-08-08 — DPDP age gate: structural block only, no consent-collection UX

**What changed:** `birth_year` is captured and under-18 users without `guardian_consent_at` are
blocked from AI-feature endpoints (`403 AUTH_GUARDIAN_CONSENT_REQUIRED`). No flow exists to
actually collect guardian consent (no email-to-guardian, no consent UI).

**Why:** The blocking check is cheap and correctness-critical (DPDP Act 2023 compliance, and many
BCA first-years are 17) — worth having from day one. The collection UX is expensive and nothing
depends on it yet since there's no real user base.

**What it costs:** An under-18 registrant is currently blocked from AI features with no path to
unblock themselves. Acceptable short-term for a dev build; must be resolved before real under-18
users are expected to use the product.

---

## 2026-08-08 — Embedding provider: Voyage AI, model `voyage-4-lite`

**What changed:** Groq doesn't serve embeddings (confirmed — chat/completion only). Voyage AI's
`voyage-4-lite` model is used for all chunk embeddings.

**Why:** The user has a Voyage AI API key and asked for "whichever pairs best with Groq" — since
there's no technical coupling between a chat provider and an embedding provider, the choice was
made on cost/quality fit: `voyage-4-lite` is $0.02/M tokens with 200M free tokens/account (current
as of Aug 2026), which suits a cost-sensitive, bursty student product well. Voyage AI is also a
well-regarded retrieval-quality option among hosted embedding APIs.

**What it costs:** Output dimension must be confirmed via a live API call before the
`chunk_embeddings.embedding vector(N)` column is migrated (never guessed). Switching embedding
providers later requires a re-embed migration, not just a config change — `model`/`model_version`
are stored per row specifically to make that migration tractable.

---

## 2026-08-08 — DOCX/PPTX parsing deferred

**What changed:** Ingestion supports PDF, TXT, and MD only this phase.

**Why:** Keeps the first vertical slice's ingestion pipeline scope tight; DOCX/PPTX parsing
(Apache POI) is a straightforward but separate addition with its own edge cases (macro-enabled
`.docm` rejection, table/list handling).

**What it costs:** Students with `.docx` slide decks or Word notes can't upload them yet. Revisit
in Phase 5.

---

## 2026-08-08 — Retrieval (hybrid search / RRF / rerank) deferred

**What changed:** Summary generation (the only AI feature in Phase 1) is map-reduce over a
document's own chunks in stored order — no vector search, no lexical search, no fusion, no
reranking is implemented.

**Why:** Summaries don't need retrieval — they operate on the whole document. Retrieval only
becomes necessary for tutor chat (Phase 2). Building it now would be scaffolding ahead of a
feature that needs it, which the spec's own §0 explicitly warns against.

**What it costs:** Nothing yet — embeddings are still generated and stored during ingestion
specifically so Phase 2 doesn't need a re-ingestion migration to start using them.

---

## 2026-08-08 — Java 21 language level on JDK 24

**What changed:** Maven compiles with `--release 21` even though the installed JDK is 24.

**Why:** Java 21 is the current LTS Spring Boot 3.x officially targets; pinning to it keeps the
build portable to any CI/deploy environment on JDK 21+, while still running fine locally on the
installed JDK 24.

**What it costs:** Nothing — this is a compile-target choice, not a runtime constraint.
