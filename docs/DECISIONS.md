# Decisions Log

Every deviation from the master build spec, dated, with what changed, why, and what it costs.
Silent deviation is the failure mode; this log is what makes deviation legitimate instead.

---

## 2026-08-08 — Phase 3 checkpoint 15: key points + shared batch repair loop

**What changed:** Key points extraction was implemented first for batch-study generation, with a
shared `BatchRepairLoop` that keeps valid items from a batch, repairs only malformed ones once,
and fails only if zero items survive. The frontend now has a dedicated key-points page and the
backend exposes `/documents/{id}/key-points`.

**Why:** Key points are the smallest useful batch feature and the right place to establish the
shared partial-success pattern MCQs and flashcards will reuse later. The richer `ChunkView`
fields were also surfaced here so later batch features can steer citations by chunk/page/section
without another data-model pass.

**What it costs:** The batch repair heuristic and citation-shape details were intentionally
designed for this build because the master spec text was not available in full detail here. That
makes the implementation explicit and testable, but it means later spec recovery should review
these choices before extending them to MCQs/flashcards.

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
login). **This is explicitly not production-suitable** — acceptable only for this dev build with
no real user base. The eventual fix, once real email delivery exists, is to make
`POST /auth/register` return a uniform "check your email" acceptance response regardless of
whether the address is already registered, and move account-creation-or-no-op behind the
verification-link click instead of the synchronous 201 this phase returns — not a smaller patch
on top of the current synchronous flow. Do not ship the current 409 behavior against real user
data.

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

---

## 2026-08-08 — Refresh/logout CSRF: interim mitigation, not the deferred double-submit token

**What changed:** `SecurityConfig`'s cookie defaults (`application.yml`) flipped to
`secure: true` / `same-site: Strict` as the base (prod-appropriate) config. Local dev now runs
with a `local` Spring profile (`application-local.yml`, activated by `run-dev.sh` via
`-Dspring-boot.run.profiles=local`) overriding to `secure: false` / `same-site: Lax`, since
`http://localhost` can't satisfy `Secure`. Separately, `POST /auth/refresh` and `POST
/auth/logout` (`AuthController`) now reject requests missing a client-supplied `X-Request-Id`
header with `400 VALIDATION_FAILED`.

**Why:** These two endpoints are cookie-authenticated and CSRF-exempt in `SecurityConfig` (no
Bearer token in play), which was previously only mitigated by documenting the double-submit
token as a deferred TODO with no interim protection at all. `SameSite=Strict` alone is a
same-site-only cookie (frontend and API are expected to share a registrable domain, e.g.
`app.studyflow.ai` / `api.studyflow.ai`, so this doesn't break the CORS-configured
credentialed-fetch flow between them) but doesn't fully close the gap on its own. Requiring a
custom header is a real, if lightweight, second layer: a cross-site `<form>`/`<img>`/link
submission cannot set arbitrary headers, only same-origin (or CORS-permitted) `fetch`/XHR can —
so this blocks naive CSRF without needing a token to be threaded through frontend state yet.

**What it costs:** This is not the double-submit token the original spec calls for — a
same-site attacker able to run JS on an allowed CORS origin (not just a cross-site page) could
still set the header. The real fix (a per-session anti-CSRF token, verified server-side against
the cookie) is still deferred; revisit alongside real session/CSRF-token infrastructure. If a
future deployment puts the frontend and API on genuinely different registrable domains,
`SameSite=Strict` would need revisiting too (would break the cookie entirely, not just weaken
CSRF protection).

---

## 2026-08-08 — Phase 2 retrieval parameters and grounding contract: designed, not pulled from spec

**What changed:** `specs/09-rag.md` §Retrieval and the tutor grounding contract were marked
"deferred, full detail in the original spec" — but the original master spec's §7 (Retrieval) and
the tutor half of §8 were never actually pasted (see `specs/15-PENDING.md`: the paste truncated
mid-§12, and nothing from §13 onward, including whatever numbering covered retrieval detail if it
was later in the doc, ever arrived). Starting Phase 2 needs concrete numbers that don't exist
anywhere in this repo or in the approved build plan (checked both). Rather than block Phase 2 on
a paste that may never come, the following was designed from scratch, using the constraints that
*are* documented (pgvector HNSW index already live with `vector_cosine_ops`, `ef_construction=64`;
product promise is "only answers from your notes, with citations"; DECISIONS.md's own precedent
that a documented decision beats a silent gap):

- **Conversation scope:** one conversation belongs to exactly one document (`POST
  /documents/{id}/conversations`), matching every other AI feature this build has (summaries are
  per-document too). Cross-document / whole-library tutor chat is not this phase's design — no
  spec text asked for it, and single-document scope is what the existing retrieval index
  (`document_chunks`/`chunk_embeddings`, both carrying `document_id`) is already shaped for.
- **Vector arm:** top 20 nearest chunks by pgvector cosine distance (`<=>`), scoped to
  `(document_id, owner_id)`, `hnsw.ef_search=40` per session (the value `specs/02-data-model.md`
  already reserved for "once retrieval exists").
- **Lexical arm:** Postgres full-text search. `document_chunks` gets a generated
  `content_tsv tsvector` column (`to_tsvector('english', content)`) + GIN index (V12 migration).
  Query via `plainto_tsquery('english', ?)`, ranked by `ts_rank_cd`, top 20, same
  `(document_id, owner_id)` scope.
- **Fusion:** Reciprocal Rank Fusion, `k=60` (the standard RRF constant from the original TREC
  paper, and the value most hybrid-search writeups converge on absent a reason to tune it) —
  `score(chunk) = Σ 1/(60 + rank_in_list)` over whichever of the two ranked lists the chunk
  appears in. Top 8 fused chunks proceed to the next step. **No separate rerank stage** — the
  original spec's "RRF + rerank" pairing implies a cross-encoder (or similar) re-scoring pass
  after fusion; that's a real quality lever but also real added latency and a new model
  dependency, and without the eval harness (Phase 3) to measure whether it actually improves
  citation relevance for this document set, adding it now would be tuning against nothing. RRF
  alone is a reasonable baseline; revisit once Phase 3's eval harness can justify the cost.
- **Neighbour expansion:** for each of the top-8 fused chunks, also pull `chunk_index - 1` and
  `chunk_index + 1` from the same document (if present), deduplicated, capped at 16 total chunks
  — keeps a citation's immediate context intact (a chunk boundary can land mid-explanation) without
  ballooning the prompt.
- **Confidence floor:** the best cosine similarity among the vector arm's hits (`1 -
  min_distance`) is the grounding signal. Below **0.35**, the retrieved material is judged too
  weak to answer from — the assistant responds with a refusal message (`grounded: false`, empty
  citations) instead of calling the model on thin context, *unless* the "explain beyond my notes"
  toggle is on for that message. 0.35 is a chosen threshold, not a measured one — there's no eval
  harness yet (deferred to Phase 3, see `specs/08-ai-layer.md`) to tune it against; revisit once
  one exists. This refusal is a normal chat message, not an HTTP error — "the tutor doesn't know"
  is expected product behaviour, not a failure.
- **Citations, mechanically:** chunks selected by retrieval are given a numbered manifest in the
  prompt (`[1]`, `[2]`, …); the model is asked to cite inline using those numbers. After the
  stream completes, citation markers are extracted by regex and mapped back to the fixed candidate
  list — any out-of-range marker is dropped, never repaired. This sidesteps needing JSON-mode
  structured output (and the summary feature's repair-loop) for a free-form streamed chat answer,
  while keeping the same "never trust the model's self-reported reference" discipline: the
  citation set is bounded by what retrieval actually returned, not by what the model claims.
- **"Explain beyond my notes" toggle:** a per-message boolean. When true, the confidence floor is
  skipped and the system prompt permits general knowledge, but the model is instructed to prefix
  any non-notes content so a student can tell what's grounded and what isn't. `grounded: false` is
  still recorded on that message for the UI to render distinctly.
- **`TUTOR_OUT_OF_SCOPE` (reserved in `specs/03-api-and-errors.md`) — intentionally not
  implemented.** The spec table lists it as reserved for this phase, but nothing anywhere defines
  what "out of scope" means for a tutor beyond "not grounded in the student's notes" (which the
  confidence-floor refusal above already handles). Building a separate off-topic/content
  classifier would be inventing product behaviour with no spec basis — same discipline as
  `specs/15-PENDING.md` applies to invented content. Left as a documented gap, not a silent one.
- **Tutor model:** `openai/gpt-oss-20b` (config key `studyflow.ai.groq.models.tutor`), not the
  `openai/gpt-oss-120b` used for summaries. Interactive chat has a first-token latency budget
  (`specs/01-architecture.md`: "First token < 2.5s") that a batch summary job doesn't; the smaller
  model class is the direct lever for that, and `specs/08-ai-layer.md` already lists it as an
  available current-generation option. Revisit if quality feedback (once there's a way to collect
  it) says otherwise.
- **Quota:** tutor messages reuse the existing `usage_counters` mechanism rather than a new table.
  `usage_counters.period_ym` (`VARCHAR(7)`, month-only) is widened to `VARCHAR(10)` so a
  day-granularity key (`2026-08-08`) can share the column with the existing month-granularity keys
  — `QuotaService` gained a period-parameterised overload rather than a new service. Limit: 30/day
  (`studyflow.quota.tutor-messages-per-day`), matching `specs/06-rate-limiting.md`'s FREE-tier
  bucket table, enforced as a monthly-style atomic counter instead of the spec's L1+L2 token bucket
  (Redis is still deferred — see the existing rate-limiting deviation above). On exceeding it, the
  existing `QUOTA_AI_EXCEEDED` code is reused rather than minting a tutor-specific quota code — the
  spec's error table has no such code, and the semantics ("you've hit this month's/day's AI usage
  cap") are identical.

**What it costs:** These are load-bearing product numbers (confidence floor, RRF k, top-k sizes)
invented under real constraints rather than lifted from the master spec's actual §7. If the
missing paste ever arrives with different numbers, treat this whole entry as superseded and
re-tune against it — nothing here should be assumed authoritative over the original spec once it's
available.

---

## 2026-08-08 — Fixed: concurrent `/auth/refresh` returned 500 instead of 401 AUTH_REFRESH_REUSED

**What changed:** Found via manual browser E2E testing for Phase 2 (a full page reload's silent
refresh raced against another in-flight one), then reproduced deterministically with two real
concurrent `curl` calls sharing one refresh cookie. `RefreshTokenService.revokeFamily` is now
`@Transactional(propagation = REQUIRES_NEW)` and called via a lazily-injected self-reference
(`self.revokeFamily(...)`, same pattern `JobLifecycleService` already uses) instead of a bare
same-class call; `RefreshTokenService.rotate` and `AuthService.refresh` dropped their
`noRollbackFor = ApiException.class`.

**Why:** Two bugs stacked. (1) `rotate()`'s reuse-detection catch block called `revokeFamily(...)`
as a plain `this.`-style call — Spring AOP proxies never intercept same-class self-invocations, so
`@Transactional(REQUIRES_NEW)` on `revokeFamily` was syntactically present but functionally inert;
it silently joined `rotate()`'s already-failed transaction instead of getting a fresh Hibernate
session. (2) The `saveAndFlush(current)` that threw `ObjectOptimisticLockingFailureException`
already left that persistence context unreliable for further writes — `revokeFamily`'s own
`saveAll` inside it re-threw essentially the same exception, past the catch block, as an unhandled
500. Once `self.revokeFamily(...)` correctly went through the proxy and got its own transaction,
a *second*, related issue surfaced: `rotate()`/`refresh()`'s `noRollbackFor` (originally added so
the revocation wouldn't be undone by a rollback) was no longer just unnecessary but actively
harmful — with the revocation now persisting independently, letting the outer transaction commit
would leave the just-issued, unrevoked, still-valid child token (inserted by `issue()` before the
failing flush) permanently committed and undetected by the revocation. Dropping `noRollbackFor` so
the outer transaction rolls back on `ApiException` discards that orphan instead.

**What it costs:** Nothing functional — this is a correctness fix, not a behavior change from what
the reuse-detection design already intended. Worth remembering for any future same-class call to a
`@Transactional`-annotated sibling method: it needs to go through the bean's own proxy (self-
injection, or an external caller) or the annotation does nothing, silently. Regression test:
`AuthFlowIntegrationTest.concurrentRefreshWithTheSameCookieNeverProducesA500`.

---

## 2026-08-08 — Tutor chat message persistence: `conversations`/`messages`, no soft delete yet

**What changed:** `conversations` (id, document_id, owner_id, created_at, updated_at) and
`messages` (id, conversation_id, owner_id, role, content, citations jsonb, grounded, beyond_notes,
model, prompt_version, created_at) tables added (V12), matching the shape `specs/02-data-model.md`
already reserved for them. No `deleted_at` on either table — no delete/archive feature exists yet
for conversations, same position `summaries` already takes (soft delete exists on `documents`
because re-upload-dedup and library-list filtering need it; nothing here needs the equivalent
yet). Add it additively if/when a delete-conversation feature is built.

**Why:** Keeps the table shape minimal and matches existing precedent rather than speculatively
building delete support nothing asked for yet (see CLAUDE.md's own "don't design for hypothetical
future requirements").

**What it costs:** Nothing yet.

---

## 2026-08-08 — Voyage AI account has no payment method: hard 3 RPM / 10K TPM cap

**What changed:** Nothing in application code. Documenting a real infrastructure constraint
discovered while running Phase 2's integration tests: this build's Voyage AI account has no
payment method on file, so every request is capped at **3 requests/minute and 10K tokens/minute**
(confirmed via a direct `curl` against `/v1/embeddings`, which returned `429` with that exact
explanation). The 200M free-token allowance from the earlier embedding-provider decision above
still applies — this is a rate cap, not a spend cap.

**Why this matters:** A single real-infra test class that ingests more than one document (or
issues more than a couple of retrieval queries) in quick succession can trip this cap well within
normal test runtime, and the *whole* suite run (ingestion + summary + retrieval + tutor tests
combined) reliably exceeds 3 Voyage calls inside any 60-second window. The job engine's own
retry/backoff (`specs/07-jobs-and-async.md`) handles this correctly in production — a 429 is a
transient failure, requeued with backoff — but test helpers that wait for a job to reach a
terminal state need to keep calling `JobDispatcher.pollOnce()` while they wait (background polling
is disabled in tests for determinism), or a requeued job just sits `QUEUED` until the test's own
timeout fires. Every integration test with an `awaitJobTerminal` helper was updated to re-poll on
each wait iteration, mirroring what the real `@Scheduled` dispatcher does at runtime.

**What it costs:** A full `mvn test` run against real infrastructure can still intermittently hit
`429`s from Voyage under sustained back-to-back runs (this account tier's cap is tight enough that
even correct backoff/retry can occasionally exhaust 3 attempts inside a short window) — the
symptom is a job landing in `FAILED` with `TRANSIENT_FAILURE`/`STALE_HEARTBEAT` rather than a code
defect. Adding a payment method (per Voyage's own error message) would remove this ceiling
entirely; not this session's call to make. Until then, spacing real-infra test runs apart in time
reduces but does not eliminate the risk.
