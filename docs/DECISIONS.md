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

**Update 2026-08-08:** Confirmed live — `POST https://api.voyageai.com/v1/embeddings` with
`model=voyage-4-lite` returns 1024-dimension vectors (usage: 4 tokens for a 5-word test string).
`V7__chunk_embeddings.sql` uses `vector(1024)`.

---

## 2026-08-08 — pgvector built from source against Postgres 15

**What changed:** `pgvector` 0.8.0 was compiled and installed from source
(`PG_CONFIG=/usr/local/opt/postgresql@15/bin/pg_config make install`), not via `brew install
pgvector`.

**Why:** Homebrew's `pgvector` bottle only ships binaries built against `postgresql@17`/`@18`
(neither installed on this machine) — it silently doesn't wire up `postgresql@15`, the version
this project actually runs (see the Testcontainers deviation above). `CREATE EXTENSION vector`
failed with "extension not available" until built directly against the running server's
`pg_config`. The build itself needed the compiler's sysroot flag overridden
(`CPPFLAGS`/`LDFLAGS` with `$(xcrun --show-sdk-path)`) because `pg_config`'s baked-in flags
referenced a specific SDK version (`MacOSX14.sdk`) that isn't present on this machine — reused
`pg_config`'s own other include/lib paths rather than guessing a replacement set.

**What it costs:** Nothing functional — `vector`, `vector_cosine_ops`, and the HNSW index type all
work identically to the brew-bottled version. Worth remembering if this environment's Postgres
version ever changes: pgvector would need rebuilding the same way unless Homebrew starts bottling
for that version.

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

---

## 2026-08-08 — Spring Boot 4.0.7, not 3.x

**What changed:** The backend runs on Spring Boot 4.0.7 (Spring Framework 7, Spring Security 7,
Hibernate 7), not the 3.x line `CLAUDE.md` originally specified. `CLAUDE.md`'s tech-stack line has
been updated to match. New-style starter artifact ids apply throughout (`spring-boot-starter-
webmvc` not `-web`, `spring-boot-starter-flyway`, per-module test starters like `spring-boot-
starter-actuator-test` instead of one shared `spring-boot-starter-test`) — these only exist from
Spring Boot 4.0 onward, confirmed against Maven Central directly rather than assumed.

**Why:** The initial scaffold's `pom.xml` already used the new-style starter names (just with an
invalid `4.0.7.RELEASE` version string — modern Spring Boot dropped the `.RELEASE` suffix). Given
a choice between rewriting to match `CLAUDE.md`'s 3.x pin or fixing the version typo and keeping
4.0.7, the user chose 4.0.7 to match what was already there.

**What it costs:** A few Spring Boot 4.0 API changes had to be worked around, each verified
against the actual jar contents rather than assumed from pre-4.0 knowledge: `EndpointRequest` was
removed from Actuator's security autoconfiguration (used a plain path matcher instead);
`TestRestTemplate` moved package to `org.springframework.boot.resttestclient` and needs
`@AutoConfigureTestRestTemplate` explicitly (no longer auto-configured by
`@SpringBootTest(webEnvironment = RANDOM_PORT)` alone) plus a `spring-boot-restclient` test
dependency for `RestTemplateBuilder`. Revisit if a reason emerges to pin back to 3.5.x.

---

## 2026-08-08 — citext columns: no `@JdbcTypeCode(SqlTypes.OTHER)`

**What changed:** `User.email` (Postgres `citext`) is mapped as a plain JPA `String` with
`@Column(columnDefinition = "citext")` and no `@JdbcTypeCode` override.

**Why:** The obvious-looking fix for a Hibernate `ddl-auto=validate` type mismatch on a custom
Postgres type (`found [citext], expecting [varchar(255)]`) is `@JdbcTypeCode(SqlTypes.OTHER)`.
That fixes validation but breaks every query against the column at runtime — Hibernate then binds
the parameter as `bytea`, and Postgres rejects it (`operator does not exist: citext = bytea`).
`columnDefinition = "citext"` alone satisfies the validator without changing how the parameter is
bound, so plain-`String` binding (which citext accepts natively) keeps working.

**What it costs:** Nothing functional — this is a corrected mapping, not a simplification. Worth
remembering if another `citext`/custom-Postgres-type column is added later.

---

## 2026-08-08 — Registration doesn't fully hide email-already-registered

**What changed:** `POST /auth/register` returns `409 AUTH_EMAIL_ALREADY_REGISTERED` (a new error
code, not in the original spec table) when the email is already taken — this does reveal account
existence. Login-side enumeration mitigation (uniform `AUTH_INVALID_CREDENTIALS` for both wrong
password and unknown email, with a dummy BCrypt check to normalize timing) is fully implemented.

**Why:** The spec's "no user enumeration" note covers both login and registration, but full
non-enumeration on registration structurally requires an async, email-verification-gated flow
("if this email isn't registered, you'll get a link") — which conflicts with this phase's
auto-verify, synchronous-201-response registration (itself a documented deviation above). Building
the async flow just to hide this would be scope creep ahead of a feature (real email delivery)
that isn't built yet.

**What it costs:** An attacker can enumerate registered emails via the register endpoint (not via
login). Acceptable for a dev build with no real user base; revisit alongside real email delivery.

---

## 2026-08-08 — Error codes added beyond the original table

**What changed:** `specs/03-api-and-errors.md`'s error code table has been extended with
`AUTH_EMAIL_ALREADY_REGISTERED` (409), `DOCUMENT_NOT_FOUND` (404), `SUMMARY_NOT_FOUND` (404),
`NOT_FOUND` (404, unmapped routes), and `INTERNAL_ERROR` (500, unhandled exceptions).

**Why:** The original table covers the codes the spec text called out explicitly, but building the
actual endpoints surfaced a few gaps it didn't spell out (an owner-scoped GET/DELETE needs a
not-found code; the RFC 9457 handler needs *something* to return for a truly unexpected exception
or an unmapped route). Adding them here rather than leaving them as unlabeled ad hoc strings in
code.

**What it costs:** Nothing — this is filling a gap, not a deviation from intent.

---

## 2026-08-08 — chunk_embeddings insert needs an explicit JPA flush first

**What changed:** `RagIngestionServiceImpl.ingest()` calls `chunkRepository.flush()` immediately
after saving `document_chunks` rows and before `ChunkEmbeddingDao.insert(...)` (raw JdbcTemplate)
writes the corresponding `chunk_embeddings` rows.

**Why:** `document_chunks` is a JPA entity (Hibernate defers INSERTs until flush time);
`chunk_embeddings` is written via plain JdbcTemplate (see the pgvector entry above — no Hibernate
mapping for the `vector` type) on the same connection/transaction. Without the explicit flush, the
raw JDBC insert ran before Hibernate had actually sent its pending chunk INSERTs to Postgres, and
the FK from `chunk_embeddings.chunk_id` to `document_chunks.id` failed
(`insert or update ... violates foreign key constraint`) even though the code that "created" the
chunk had already run.

**What it costs:** Nothing — one extra `flush()` call. Worth remembering any time JPA-managed
writes and raw-JDBC writes touch the same rows within one transaction: the ordering that looks
correct in the Java source isn't necessarily the ordering that reaches the database.
