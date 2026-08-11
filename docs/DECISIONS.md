# Decisions Log

Every deviation from the master build spec, dated, with what changed, why, and what it costs.
Silent deviation is the failure mode; this log is what makes deviation legitimate instead.

---

## 2026-08-11 — Free-tier deployment: Supabase Storage, single-jar bundling, Render

**What changed:** Added `SupabaseStorageProvider` (`RestClient` over Supabase Storage's REST API,
same posture as `RedisLoginLockStore`'s Upstash integration — one bucket, three verbs, no SDK
dependency) as a second `StorageProvider`, selected via `studyflow.storage.provider`
(`local`/`supabase`, default `local`, both `@ConditionalOnProperty`-gated so exactly one bean
exists at runtime). Cloudinary stays permanently out per the existing entry below — this isn't
that decision reversed, it's a different provider for the same "free hosts have ephemeral disk"
problem `LocalDiskStorageProvider` can't solve on its own.

Also added a root `Dockerfile` that builds the Vite frontend and copies its output into
`src/main/resources/static` before packaging the backend jar — frontend and backend deploy as one
image, one origin. `SpaWebConfig` (new, `@ConditionalOnResource` on the bundled `index.html`)
serves it with an `index.html` fallback for React Router's client-side routes. `server.port` now
binds to `${PORT:8080}` for hosts (Render) that assign the listen port at runtime.

Database stays Neon, as already planned below — confirmed, not changed, when picking a compute
host for this deployment.

**Why:** Splitting frontend and backend across two free hosts (e.g. Vercel + Render) would put
them on different origins, and `studyflow.cookie.same-site: Strict` silently drops the refresh
cookie on any cross-origin fetch — auth breaks in prod, not in a way anything currently tests for
since local dev already works around the equivalent problem with the `local` profile's
`same-site: Lax`. Bundling into one image avoids that class of bug entirely rather than adding a
`None`/cross-site cookie path that only exists for this deployment shape. Supabase Storage was
picked over Cloudinary (still out) and over accepting ephemeral storage because the user already
has a Supabase account — no new signup — and free-tier persistent object storage is the actual
gap ephemeral container disks leave for a document-upload product.

**What it costs:** Render's free web service spins down after 15 minutes idle (~30-50s cold start
on the next request) — not fixable without a paid tier. `SupabaseStorageProvider` has no
integration test against a real Supabase project (would need a second free account provisioned
just for CI); it's a straight structural mirror of the already-tested `RedisLoginLockStore` REST
pattern, but flagging the gap rather than claiming coverage that doesn't exist. Full runbook:
`docs/DEPLOYMENT.md`.

---

## 2026-08-11 — Google + GitHub OAuth social login

**What changed:** Added Google and GitHub as alternate sign-in methods alongside email/password,
via Spring Security's `oauth2Login` (`CommonOAuth2Provider`, no explicit authorization/token/
jwk-set URIs needed for either). An OAuth sign-in ends with the identical access-token +
HttpOnly-refresh-cookie pair a password login produces (`AuthService.oauthLogin`, mirroring
`login()`'s tail) — nothing downstream needs to know which method was used. No dedicated frontend
callback page: `OAuth2LoginSuccessHandler` sets the cookie and redirects straight to
`/library`; `AuthContext`'s existing mount-time `apiRefresh()` picks it up.

- **Match-by-verified-email, no linking table.** An OAuth email that matches an existing
  (however-created) account signs into it. Provider-verified email is trusted at the same level
  as a password-reset loop; no `oauth_accounts` table since nothing needs multi-provider
  bookkeeping yet.
- **`users.birth_year` becomes nullable** (`V22__users_birth_year_nullable.sql`, `User.birthYear`
  now boxed `Short`). Neither provider's profile includes birth year, which the DPDP age gate
  needs. `null` means "hasn't completed the post-OAuth profile step" — `DpdpGuard` throws the new
  `AUTH_BIRTH_YEAR_REQUIRED` (403) before its existing minor/consent check, reusing every AI-
  feature call site with no new gate wiring. `/me` exposes `birthYearRequired`; the frontend's
  `ProtectedRoute` redirects to a new `/complete-profile` page (`PATCH /me/birth-year`) until it's
  set.
- **GitHub's `email` can be null** for private-email accounts — `OAuth2UserInfoResolver` requests
  `user:email` scope and falls back to a real call to `/user/emails` with the access token Spring
  already obtained, preferring the verified+primary address. Never guesses; throws rather than
  creating a broken account if no verified email can be resolved.
- **OAuth-created accounts get a random, unusable BCrypt hash** (`AuthService`'s existing
  `dummyHash`) instead of a nullable `password_hash` column — every other password-comparison path
  stays unchanged. `email_verified_at` is set immediately (the provider verified it, more honest
  than password registration's existing auto-verify deviation).
- **Session policy: `STATELESS` → `IF_REQUIRED`.** OAuth2Login's default authorization-request
  repository needs an `HttpSession` to stash the in-flight request between redirect-out and
  callback. `IF_REQUIRED` only creates one during that few-second handshake; every JWT-
  authenticated API call still never touches `HttpSession`. A custom cookie-based repository would
  preserve full statelessness but isn't worth the code for a handshake that lasts seconds.

**Why:** User request — wanted Google/GitHub login in addition to email/password. Considered
Clerk first (user's suggestion) but it either replaces the whole session system (defeats the
"same session model" goal) or, restricted to just its OAuth buttons, needs a hand-rolled JWKS
verifier and a third external account for less functionality than Spring Security's built-in
`oauth2Login` already provides — surfaced this tradeoff back to the user, who chose to stay with
native Spring Security OAuth2Login.

**What it costs:** Real Google/GitHub OAuth app credentials (`GOOGLE_CLIENT_ID/SECRET`,
`GITHUB_CLIENT_ID/SECRET` in `backend/.env`) are required before either button works in a browser
— until then `/oauth2/authorization/{google,github}` 500s at the provider redirect. Automated
tests can't drive the real browser-redirect + consent-screen flow, so coverage is
`AuthService.oauthLogin` directly against real Postgres (`OAuthLoginIntegrationTest`) and
`OAuth2UserInfoResolver`'s GitHub fallback against a `MockWebServer` fake
(`OAuth2UserInfoResolverTest`) — the real end-to-end path still needs a manual browser walkthrough
per this file's own verification-loop rule, same as every other phase's UI sign-off.

---

## 2026-08-11 — Groq multi-key rotation, round-robin across accounts

**What changed:** `GroqAiProvider` now round-robins the `Authorization` header across a pool of
Groq API keys (`studyflow.ai.groq.api-keys`, comma-separated `GROQ_API_KEYS` env var, falling back
to the single `GROQ_API_KEY` if unset) instead of one fixed key baked into the `RestClient` at
construction. A plain `AtomicInteger` counter, no smarter "retry a different key on 429" loop —
the job engine's own backoff/retry already re-invokes `complete`/`streamComplete` on transient
failure, and each fresh invocation naturally lands on the next key. `GroqModelAvailabilityChecker`
(a one-off startup check) still uses the single `studyflow.ai.groq.api-key`, unaffected.

**Why:** This account's real Groq tier has no payment method and a tight rate ceiling (documented
since Phase 2) — under real interactive use (not just test-suite bursts), it was sustaining
`TRANSIENT_FAILURE` on MCQ/flashcard/quiz generation for minutes at a time. Groq's rate limits are
per-API-key/account, so N independent accounts' keys genuinely multiply the effective request
budget by N — the only real fix available without a paid Groq plan.

**What it costs:** Nothing functional — 1-key deployments behave identically to before
(round-robin over a 1-element list is a no-op). Voyage AI has the same documented constraint (3
RPM, no payment method) and isn't covered by this change — only Groq was reported as the live
blocker; the identical rotation pattern would apply to `VoyageEmbeddingClient` if that becomes the
bottleneck next.

---

## 2026-08-11 — Phase 5: Testcontainers lands, supersedes the local-`studyflow_test` deviation

**What changed:** Integration tests now run against a real, hermetic Postgres started by
Testcontainers (`pgvector/pgvector:pg15` image) instead of the shared local Homebrew
`studyflow_test` database — Docker is available now (user decision), closing the gap the original
"Testcontainers -> local Postgres" entry left open.

- **One container for the whole JVM/surefire fork, not one per test class.** With 16
  `@SpringBootTest` classes, a fresh container per class would make the suite unusably slow.
  `TestcontainersPostgresExtension` is a JUnit5 global extension (auto-detected via
  `junit-platform.properties` + `META-INF/services/org.junit.jupiter.api.extension.Extension`)
  whose static initializer starts the container once, before any Spring context loads, and calls
  `System.setProperty("DB_URL", ...)` — this deliberately avoids editing all 16 existing test
  files to extend a shared base class or add `@DynamicPropertySource`. `src/test/resources/
  application.yml`'s datasource block now reads `${DB_URL}`/`${DB_USER}`/`${DB_PASSWORD}` (was
  hardcoded to `jdbc:postgresql://localhost:5432/studyflow_test`) — the system properties win
  over the real `.env` `DB_URL` because JVM system properties outrank OS environment variables in
  Spring's property source order.
- **`withInitScript` doesn't work in Testcontainers 2.0.5** (`NoClassDefFoundError` on a missing
  shaded `commons-io` class internal to that method) — not used; see the migration fix below
  instead, which makes any container-level workaround unnecessary.
- **Found and fixed a real, previously-hidden migration-ordering bug**: `V7__chunk_embeddings.sql`
  (uses the `vector` column type) runs before `V10__pgvector_extension.sql` (creates the
  extension). This only ever worked against a database where pgvector had been manually
  pre-installed out-of-band before Flyway first ran — exactly the kind of gap hermetic,
  from-scratch testing exists to catch, and it wasn't just a test-only problem: recreating the
  local `studyflow_dev` database (to clear the unrelated V12/V13 drift from the entry below) hit
  the identical failure starting the real dev server, proving this was a genuine bug in the
  migration sequence itself, not a Testcontainers quirk. `V7`/`V10` are already-applied,
  checksummed migrations elsewhere and can't be renumbered (same immutability lesson as that
  entry) — fixed instead with a new `V6.1__pgvector_extension_early.sql` (Flyway supports dotted
  version numbers to insert a migration between two existing ones), which creates the extension
  before `V7` needs it; `V10`'s own `CREATE EXTENSION IF NOT EXISTS` still runs harmlessly
  afterward. This is a real, permanent fix in the migration sequence, not a container-side
  workaround — `TestcontainersPostgresExtension` needs nothing extra for it.
- **Version pinned explicitly** (`org.testcontainers:postgresql:1.21.4`, the latest 1.x as of this
  session) rather than 2.x (also available) — 1.x's API (`PostgreSQLContainer`, `DockerImageName`,
  `asCompatibleSubstituteFor`) is the one this change was written and verified against; 2.x may
  have restructured packages, not worth the risk for an unverified jump.

**Why:** Removes the "must have a pre-configured local Postgres with pgvector built from source"
onboarding step entirely — `mvn test` now works on a clean machine with only Docker installed, and
every run gets a genuinely fresh database (no more manual-setup drift like the `studyflow_dev`
V12/V13 checksum incident earlier this phase).

**What it costs:** `mvn test` now needs Docker running (was previously optional if a local
Postgres 15+pgvector already existed). First-run image pull adds a few seconds; per-class latency
is otherwise comparable to the old shared-DB setup. Full non-eval suite: 101/109 passed on real
Postgres+Groq+Voyage+Upstash in one run; the 8 failures were the already-documented Groq/Voyage
rate-limit shape (7, see every prior phase's identical caveat) plus one `JobDispatcherIntegrationTest`
timing flake under full-suite load — confirmed 4/4 passing standalone immediately after, so not a
Testcontainers regression, same "each test class passes cleanly run on its own" caveat already
accepted throughout this project.

---

## 2026-08-11 — Phase 5: Redis L2 login-lock durability (Upstash REST, no TCP client)

**What changed:** Cloudinary and Razorpay are out by user decision — local disk storage and no
billing stay permanent, not "revisit when an account exists." Redis (Upstash) and Docker
(Testcontainers) were unblocked instead; this entry covers the first Redis slice: durable,
restart-surviving login lockouts.

- **Upstash's REST API, not a TCP Redis client.** One command a login check needs (`SET ... EX`,
  `TTL`) doesn't justify adding `spring-boot-starter-data-redis` + Jedis/Lettuce when the app
  already has an HTTP client (`RestClient`, same as the Groq/Voyage adapters) and Upstash's REST
  API is plain `POST /{command}/{args...}`. `RedisLoginLockStore` mirrors
  `VoyageEmbeddingClient`'s `RestClient` construction pattern exactly.
- **`LoginLockStore` is an interface** (one real implementation) purely so
  `LoginRateLimiterTest` can stay a fast, deterministic, network-free unit test of L1's arithmetic
  — same reason that test already injects a fake `Clock` instead of calling `Instant.now()`
  directly. The real L2 durability behavior gets its own real-Upstash test in
  `LoginRateLimitIntegrationTest`.
- **L2 stores only the established lock, not the sub-threshold failure count.** A restart forgets
  a partial streak (0-4 failures) and starts the window over — a minor, acceptable leniency. It
  cannot forget an actual lock: `recordFailure` writes the lock through to Redis the moment L1
  crosses the threshold, and `checkNotLocked` falls through to a Redis `TTL` check whenever L1 has
  no record for that key (the case a restart produces). This is deliberately narrower than
  durably tracking every count — durable protection against *sustained* attack is the property
  that matters, per this repo's own existing L1 framing; forgiving a forgotten partial streak
  isn't a security hole.
- **Keys are `SHA-256(normalized email)`, never the raw address** — same posture as
  `refresh_tokens.user_agent_hash`/`ip_hash`, and sidesteps ever needing to URL-encode an
  arbitrary email into a REST path segment.
- **Fails open on any Redis error** (caught `RestClientException`, logged, treated as "not
  locked"). A Redis hiccup must not become a self-inflicted login outage; L1 alone already
  protected every account before this change and still does if L2 is unreachable.
- **No `clear()` on login success.** Redis TTLs self-expire; `recordSuccess` only ever fires after
  `checkNotLocked` already found no active lock (L1 or L2), so there is nothing for L2 to hold at
  that point — a `DEL` call there would just be a wasted round trip.

**Why:** Closes a real gap in the L1-only limiter shipped earlier this phase: an attacker who can
force or wait out an app restart previously got a free reset. L2 makes the lock itself durable
without paying for full cross-instance failure-count sync, which nothing in this deployment needs
yet (single instance).

**What it costs:** One new external dependency (a free-tier Upstash account) instead of zero.
`LoginRateLimiterTest` (5 tests, `Clock` + a no-op `LoginLockStore` stub, no network) and
`LoginRateLimitIntegrationTest` (3 tests, real Postgres + real Upstash, including
`aLockKnownOnlyToRedisStillBlocksLogin` — seeds L2 directly, bypassing L1, to prove the exact
post-restart scenario) both pass. `ArchitectureTest` unaffected (32/32 — no new owner-scoped
repository). **Found and fixed along the way:** `src/test/resources/application.yml` is a
hand-maintained, full duplicate of the main `application.yml` for the test classpath (not a
profile overlay) — any new top-level `studyflow.*` config key silently doesn't exist in tests
unless added to both files. This cost real debugging time (`PlaceholderResolutionException`
looked like a broken env var, but `System.getenv` proved the OS environment was fine — the test
classpath's `application.yml` was simply a different, older file). Worth remembering for the next
new config key.

---

## 2026-08-11 — Phase 7 opens early: study planner, before Phase 5/6 finish

**What changed:** Started Phase 7 (`study_plans`/`study_sessions`, migration `V21__study_plans.sql`,
`com.studyflow.planner` package) ahead of finishing Phase 5's remainder or starting Phase 6 —
Cloudinary, Redis, Testcontainers, observability (Phase 5) and Razorpay (Phase 6) all remain
blocked on external credentials/tools not available in this environment. The planner needs none,
so it's the actual next unblocked work; sequencing by what's buildable, not by roadmap order.

- **Study-plan build is synchronous, not the async job `specs/01-architecture.md`'s table
  classifies it as.** An exam date plus a fixed spacing cadence is pure arithmetic — no LLM call,
  nothing that takes 20-180s. Manufacturing an LLM call just to match the table would be the
  opposite of honoring it (the same reasoning kept quiz-build async in Phase 4, for the opposite
  reason: it genuinely calls one). `POST /documents/{id}/study-plans` returns `201` synchronously.
  Not gated on `DocumentStatus.READY` or DPDP consent either — no chunk/text access, no AI cost,
  same posture as `FlashcardController.review`.
- **Spacing cadence** (invented, no master-spec text ever covered planner numbers — same situation
  as quizzes, see `specs/15-PENDING.md`): fixed days-before-exam offsets
  `{21, 14, 10, 7, 5, 3, 2, 1, 0}`, filtered to whatever fits before the exam date — denser near
  the exam, standard spaced-repetition-style backload. `StudySessionScheduler` is a pure function
  (no DB/HTTP), same posture as `Sm2Calculator`/`QuizScorer`, with its own plain-JUnit test.
- **`.ics` is hand-written**, not a new dependency (e.g. iCal4j) — one `VEVENT` per session is a
  handful of RFC 5545 lines, not enough surface to justify a library.
- **No session completion tracking.** Sessions are calendar entries, not a todo list — nothing
  asked for a "mark done" mutation, so it doesn't exist. Both tables are insert-only, same posture
  as `question_sets`/`quizzes`.
- **`STUDY_PLAN_NOT_FOUND`** (404) added, same "GET by id needs a not-found code" gap as every
  prior phase.

**Why:** Same posture as every other invented-number entry — designed fresh under real
constraints, documented instead of silently assumed.

**What it costs:** Nothing functional — `StudySessionSchedulerTest` (5 cases: past exam, exam
today, 1 day out, 10 days out, 30+ days out) and `StudyPlanIntegrationTest` (2 tests: full
create→list→get→`.ics`-export round trip, not-found) both pass deterministically against real
Postgres — this is the first study-feature integration test with **no** Groq/Voyage dependency,
so no rate-limit fragility. `ArchitectureTest` is 32/32 (28 existing + 4 new for
`StudyPlanRepository`/`StudySessionRepository`).

---

## 2026-08-11 — Phase 5, checkpoints A+B: DOCX/PPTX ingestion, login-attempt L1 rate limiter

**What changed:** The first slice of Phase 5 ("Infra hardening," `specs/ROADMAP.md`) — the two
tracks that need zero external accounts, chosen specifically because Cloudinary, Redis, and
Testcontainers all remain blocked on credentials/tools not yet available in this environment (see
the existing deviation entries below for each).

**Checkpoint A — DOCX/PPTX ingestion** (migration `V20__documents_docx_pptx.sql`, widening
`documents.file_type`'s CHECK; `DocumentFileType.DOCX`/`PPTX`; new `DocxDocumentParser`/
`PptxDocumentParser`, both implementing the existing `DocumentParser` interface with zero dispatch
changes needed):

- **Dependency: `org.apache.poi:poi-ooxml:5.5.1`** — current stable as of this session (confirmed
  via Maven Central/POI's own site). Single artifact covers both XWPF (DOCX) and XSLF (PPTX).
- **Sniffing DOCX vs. PPTX**: both are OOXML zip containers sharing the same `PK\x03\x04` magic
  bytes, so `DocumentUploadService.sniff()` peeks at zip entry *names* (`word/document.xml` ->
  DOCX, `ppt/presentation.xml` -> PPTX) via `java.util.zip.ZipInputStream` — this reads local-file-
  header metadata only, never decompresses entry data, so it carries no zip-bomb risk itself and
  isn't fooled by a renamed-extension attack (a plain `.zip` renamed to `.docx` still gets
  `FILE_TYPE_UNSUPPORTED` — regression-tested).
- **Password-protected DOCX/PPTX fall through to `FILE_TYPE_UNSUPPORTED`, not `FILE_ENCRYPTED`.**
  OOXML container encryption wraps the file in an OLE2/CFB compound document (different magic
  bytes entirely), so it never matches the zip sniff. Detecting that specifically would mean
  parsing the OLE2 directory just to improve an error message for a file that's unprocessable
  either way — not built. A small, accepted gap, not a silent one.
- **Zip-bomb protection**: Apache POI's `ZipSecureFile` applies `minInflateRatio=0.01` (1%) and
  `maxEntrySize=4GiB` globally by default to every OOXML load. The existing 25 MiB upload cap
  already bounds worst-case decompression to ~2.4 GiB via the ratio check alone, but the 4 GiB
  per-entry default does nothing useful at that input size — `PoiZipBombProtectionConfig` tightens
  `maxEntrySize` to **200 MiB** at boot (a hard, deterministic backstop well above any legitimate
  DOCX/PPTX entry under the upload cap). `minInflateRatio` is left at POI's own default — already
  the conservative, widely-used threshold. Residual gap: `ZipSecureFile` bounds *per-entry*, not
  cumulative, decompression across a whole archive with many entries each individually under
  threshold — closed cheaply for PPTX specifically with a **300-slide cap**
  (`PptxDocumentParser`, reusing the existing `FILE_TOO_LARGE` code), generous headroom above any
  realistic lecture deck. DOCX has no equivalent per-unit count (whole document = one page), so no
  analogous cap applies there; DOCX is also far less prone to extreme compression ratios (XML/text-
  heavy, not image-heavy) than a PPTX deck.
- **DOCX = one `ParsedPage`** (whole document), same reasoning `PlainTextDocumentParser` already
  applies to TXT/MD: DOCX has no reliable structural page-boundary concept (page breaks are a
  layout-engine computation, not a stored fact). **PPTX = one `ParsedPage` per slide**, mirroring
  `PdfDocumentParser`'s per-page loop, since PPTX does have a natural page unit. PPTX also reuses
  PDF's "avg chars/page < 100 -> `FILE_NO_TEXT_LAYER`" scanned-image heuristic — arguably more
  relevant here given how common image-heavy slide decks are. Speaker notes are deliberately not
  extracted — "page = what's visibly on the slide," matching PDF's contract.
- **Testing**: a new layer for this codebase — plain-JUnit parser unit tests
  (`DocxDocumentParserTest`/`PptxDocumentParserTest`, no Spring context, generating minimal valid
  bytes with POI itself rather than a checked-in binary fixture, since there's no trivial hand-
  writable minimal-OOXML string the way `MINIMAL_PDF` exists for PDF). One real end-to-end test
  (`PptxIngestionIntegrationTest`, real Postgres + real Voyage) — PPTX only, not DOCX, since DOCX's
  pipeline shape is structurally identical to the already-proven TXT/MD path; PPTX's multi-page
  shape is the genuinely new interaction (multiple `ParsedPage`s through chunking), avoiding
  doubled real-embedding-API cost for redundant coverage.

**Checkpoint B — login-attempt L1 rate limiter** (`LoginRateLimiter`, `ErrorCode.RATE_LIMITED`):

- **This closes a real, pre-existing gap, not a new feature.** `specs/06-rate-limiting.md` and this
  file's own earlier "Upstash Redis -> none" entry both already *claimed* an in-process L1
  login-attempt limiter existed this whole time — a full code search found it was never actually
  built. Everything below just makes that claim true.
- **In-memory only** (`ConcurrentHashMap<String, Bucket>`, `Bucket` an immutable record, mutated
  via `compute()` — atomic per key, no global lock), no new dependency (Bucket4j/Caffeine/etc. were
  considered and skipped — the one thing a library buys, cross-instance correctness, is explicitly
  L2/Redis's later job, not L1's, per the spec's own two-tier framing). Resets on restart —
  intentional, matches "L1: cheap, absorbs bursts."
- **Numbers**: 5 failures / 15 min window (spec-given). Exponential lockout, invented (spec only
  says "then exponential lockout," no formula): `minutes = min(60, 1 * 2^tier)` — 1, 2, 4, 8, 16,
  32, capped at 60. Tier persists across lockout cycles for the same bucket, resets only on a
  success or 2h of idle eviction (`@Scheduled(fixedDelay=300_000)`, folded directly into
  `LoginRateLimiter` since nothing else consumes this map). The 60-minute cap (not unbounded) is
  deliberate: this app has no admin-unlock flow yet, so an unbounded lock could permanently strand
  a real student out of their own account; durable protection against sustained attack is
  explicitly L2/Redis's later job.
- **Email normalization is correctness-critical**: keyed on
  `email.strip().toLowerCase(Locale.ROOT)`, matching `users.email`'s `citext` semantics. Without
  it, case variations of the same address would each get an independent attempt budget — a
  complete bypass (regression-tested).
- **Where the check runs, split across two layers**: `checkNotLocked` in `AuthController.login()`
  (before `authService.login(...)` is ever called, so a locked-out request never opens a DB
  transaction or runs BCrypt); `recordFailure`/`recordSuccess` inside `AuthService.login()` itself,
  since they depend on the actual DB-lookup-and-password-check outcome. No filter/interceptor —
  `AuthController.login()` is the only call site, and a filter would need its own request-body-
  parsing complexity for a decision scoped to one endpoint's one field.
- **User-enumeration resistance preserved**: both calls key on the raw submitted email string,
  never on whether the account exists — the existing dummy-hash timing-safe path is untouched. The
  locked-out response (429 vs 401) is unavoidably distinguishable regardless of which layer checks
  first; rejecting early doesn't leak anything the response wouldn't already disclose.
- **`java.time.Clock` injected** (new bean in `SecurityConfig`, alongside the existing
  `passwordEncoder()`) rather than calling `Instant.now()` directly, so `LoginRateLimiterTest` can
  advance a fake clock deterministically instead of a real `Thread.sleep` — same spirit as this
  repo's existing "backdate a DB timestamp" pattern for time-dependent tests
  (`FlashcardGenerationIntegrationTest`, the quiz EXAM-expiry test), applied to in-memory state.
- **Known, explicitly-accepted residual gap**: no per-IP throttle yet, so many distinct fake emails
  (each individually under the lockout threshold) could still grow the map's entry count within a
  2h window before the sweep catches up. This is exactly what `specs/06-rate-limiting.md`'s
  separate per-IP bucket is for — already correctly deferred to the full L1+L2 matrix when Redis
  lands, not a new gap introduced here.

**Why:** Both were chosen as the first Phase 5 slice specifically because they need no external
credentials — Cloudinary, Redis, and Testcontainers all remain blocked on accounts/tools the
project doesn't have yet (see the existing entries below). Per this repo's own §0 rule, one working
vertical slice at a time rather than shallow scaffolding across all five Phase 5 tracks at once.

**What it costs:** Nothing functional. `ArchitectureTest` stays 28/28 (neither checkpoint adds an
owner-scoped repository). `DocxDocumentParserTest`/`PptxDocumentParserTest` (7 tests),
`DocumentUploadIntegrationTest` (7 tests, 3 new), `PptxIngestionIntegrationTest` (1 test, real
Postgres + real Voyage), `LoginRateLimiterTest` (5 tests), `LoginRateLimitIntegrationTest` (2
tests, real Postgres), and the pre-existing `AuthFlowIntegrationTest` (regression check, 6 tests)
all pass. Frontend `tsc -b`/`npm run build`/`oxlint` clean. Manually verified end to end against a
running local instance (pointed at `studyflow_test`, not `studyflow_dev` — see the existing
migration-drift entry below): real `.docx`/`.pptx` uploads reached `READY` with correct file
type/MIME/char count; 5 real failed logins against a real account then a 6th returned `429` +
`Retry-After: 60` + `RATE_LIMITED` + `retryAfterSeconds: 60` exactly as designed. The rest of Phase
5 (Cloudinary, Redis L2/SSE/pub-sub, Testcontainers, observability) starts once the user confirms
the relevant credentials/tools are ready.

---

## 2026-08-10 — Phase 4: quiz build/mode/scoring design, server-authoritative timing

**What changed:** Quizzes (`quizzes`, `quiz_attempts`, `quiz_answers`, migration
`V18__quizzes.sql`) landed as a thin wrapper around the existing MCQ pipeline rather than a new
generation path: `POST /documents/{id}/quizzes` (`Idempotency-Key` required, `QUIZ_BUILD` job
type) calls the unmodified `McqGenerationService.generate(...)` to produce a fresh
`question_sets`/`questions` batch, then `QuizGenerationService` wraps the result in a `Quiz` row
carrying mode-derived timing/scoring config. No new prompt, no new validation logic — the same
difficulty mix, Bloom pairing, chunk-coverage steering, and partial-success ("N of M questions")
contract from Phase 3 apply unchanged. As with every other invented product number in this repo,
none of this comes from the master spec — `specs/10-study-features.md` marks quiz detail as
"preserved in the pasted master spec," but per `specs/15-PENDING.md` that paste never arrived and
never covered quizzes specifically. Fresh design calls, same posture as Phase 3's MCQ/SM-2
numbers:

- **Three modes, concretely differentiated, not just labeled:**
  - **EXAM** — hard server-authoritative deadline (`time_limit_seconds = questionCount * 90`,
    ~JEE/NEET MCQ pacing). Any answer-save attempted after the deadline is rejected
    (`409 QUIZ_ATTEMPT_EXPIRED`) and the attempt is auto-finalized server-side as `EXPIRED`,
    scored from whatever was saved. Negative marking: `-0.25`/wrong (JEE/NEET convention),
    `+1`/correct, `0`/unanswered. Answer key (`correctIndex`/`explanation`) is never sent to the
    client while `IN_PROGRESS` — `GET /quizzes/{id}/questions` uses a dedicated
    `QuizQuestionResponse` DTO that structurally omits both fields, unlike MCQ self-study's
    `QuestionResponse`.
  - **PRACTICE** — same time limit, shown as a countdown, but **not enforced** — answer writes
    and submit both keep working past it (client shows "Overtime," not a hard stop). No negative
    marking. Same answer-key withholding as EXAM.
  - **REVISION** — untimed (`time_limit_seconds = NULL`, no countdown UI). No negative marking.
    The one mode where `PUT /quiz-attempts/{id}/answers/{questionId}` returns `isCorrect` +
    `explanation` inline — a formative pass, not an assessment.
- **Scoring:** `score = correctCount - incorrectCount * negativeMarkingFraction`, `maxScore =
  questionCount`, unanswered = 0 (`QuizScorer`, a pure function with no DB/HTTP dependency — same
  posture as `Sm2Calculator`). `quizzes.negative_marking_fraction NUMERIC(3,2)` (`0.25` for EXAM,
  `0` otherwise) rather than a mode-keyed constant duplicated across services.
- **`submit` is always accepted and idempotent**, even past an EXAM deadline — it finalizes (or
  returns the already-finalized result) rather than erroring. Only *mid-attempt answer writes*
  hard-reject on EXAM expiry. Matches real exam UX: submit never fails, only "keep working" does.
  `QuizAttemptService.saveAnswer`/`submit`/`result`/`get` all re-check `now()` against the
  attempt's own `deadlineAt` server-side on every call — the client's countdown is a display
  computed from the server-issued `deadlineAt`, never a source of truth.
- **`quiz_attempts` carries `@Version`** (JPA optimistic locking), same rationale as `Flashcard`
  in Phase 3: a concurrent submit-vs-lazy-expire race (two tabs on the same attempt) must fail
  loudly, not silently double-score.
- **`GET /quiz-attempts/{id}/answers` was added beyond the original endpoint sketch** — resuming
  an in-progress attempt after a page reload needs to know which answers were already saved, and
  none of the other endpoints expose that pre-submission. This is safe to expose regardless of
  attempt status: it's the student's own previously saved picks, never the answer key.
- **Quiz build debits the existing `AI_JOBS` monthly quota** via the unmodified
  `JobEnqueueService.enqueue(...)` path — no new quota metric, same precedent as MCQs/flashcards.
- **OMR-bubble motif** (`specs/11-frontend.md`'s reserved "Secondary motif," deferred until now)
  implemented as `.omr-option`/`.omr-bubble` in `components.css` — a visually-hidden native radio
  input inside a styled `<label>`, so the browser's own label-click-toggles-input semantics do the
  interaction work; no custom click handling needed beyond the `onChange`.

**Why:** Reusing `McqGenerationService` unchanged means quiz build inherits Phase 3's entire
validated generation/partial-success machinery for free, and honors
`specs/01-architecture.md`'s own classification of "quiz build" as an async job (unlike a "select
existing questions" design, which would have no LLM call and no honest reason to be async).

**What it costs:** Nothing functional — `QuizGenerationIntegrationTest` (6 tests total: one
exercising EXAM negative marking, answer-key withholding, and the clear-an-answer path together;
one each for PRACTICE no-negative-marking and REVISION untimed + immediate feedback; one
deterministic EXAM-expiry test backdating `deadline_at` directly through `JdbcTemplate` rather
than a real `Thread.sleep` — same technique as Phase 3's flashcard optimistic-lock test; plus 2
cheap validation tests) passes cleanly against real Postgres/Groq — confirmed both as a full class
run and each test method in isolation.
`ArchitectureTest` is 28/28 (22 existing + 6 new tenancy rules for `QuizRepository`/
`QuizAttemptRepository`/`QuizAnswerRepository`). Running the full non-eval `mvn test` suite
back-to-back hit the same pre-existing Voyage/Groq rate-tier ceiling already documented below
(this session's cumulative real-API load pushed Groq into a *sustained*, not just transient,
rate-limited state for a stretch — see the next entry) — every quiz test class and method passes
standalone with normal spacing, matching the exact caveat already accepted for
Mcq/Flashcard/Retrieval/Tutor integration tests. Frontend: `tsc -b` strict, `oxlint` clean (no new
warnings), `npm run build` clean.

---

## 2026-08-10 — Local dev database (`studyflow_dev`) had independent migration drift from `studyflow_test`

**What changed:** Nothing in application code. While starting the backend locally
(`./mvnw spring-boot:run -Dspring-boot.run.profiles=local`) to do a real browser walkthrough of
Phase 4, Flyway failed validating `studyflow_dev` — a checksum mismatch on `V12__tutor_chat.sql`
(the file's on-disk checksum no longer matched what was recorded from an earlier local run, i.e.
someone/something touched V12 after it was already applied to this specific database). Fixed the
checksum by hand (`UPDATE flyway_schema_history SET checksum = <resolved-locally value Flyway's
own error message reported> WHERE version = '12'` — the standard, non-destructive meaning of what
`flyway:repair` does). Migrating past that surfaced a second, unrelated problem: `V13__tutor_chat.sql`
tried to `CREATE TABLE conversations`, which already existed in `studyflow_dev` — meaning V13's
DDL had been applied to this database at some point without ever being recorded in
`flyway_schema_history` (likely a manual/partial run from earlier local development, predating
this session).

**Why this matters:** This is `studyflow_dev`-specific drift, unrelated to Phase 4 — `studyflow_test`
(what the automated test suite and CI-equivalent verification actually use) was already cleanly
migrated through V18 with no issues, confirmed by every integration test run this session.
Rather than keep hand-patching a second, unrelated migration-history inconsistency on a database
whose prior manual state isn't something this session has context on, the browser walkthrough was
pointed at `studyflow_test` instead (`DB_URL` overridden for that one run only, `.env` untouched)
— clean, already-verified-through-V18, and zero risk of guessing wrong about someone else's local
dev data.

**What it costs:** `studyflow_dev` is left with the V12 checksum fixed but `V13` still unresolved
(migration would still fail if started against `studyflow_dev` as-is). Not fixed further this
session because doing so blind (either forcibly marking V13 as already-applied via
`flyway:baseline`/history-table surgery, or dropping/recreating `conversations`) risks losing real
local dev data without knowing why V13 was already half-applied outside Flyway's bookkeeping.
`flyway repair` is the right tool for the V12 checksum drift above (it recomputes a recorded
checksum to match the resolved migration file for a version Flyway already knows was genuinely
applied) — it is **not** the right tool for V13, since V13 was never recorded as applied at all;
repair has nothing to reconcile there and won't register it. A raw `INSERT` into
`flyway_schema_history` is a similarly bad fit — it fabricates history Flyway never observed,
with no built-in check that the row's checksum/shape actually matches what's live. Whoever owns
this local environment should decide: if `studyflow_dev`'s data isn't precious, easiest fix is
dropping and letting Flyway rebuild it from scratch; if it is, first take a backup (or clone the
DB) and run a real schema diff confirming the live `conversations` table exactly matches what
`V13__tutor_chat.sql` would have produced, then use Flyway's own supported mechanism for "this
migration was already applied outside Flyway" — `flyway migrate -skipExecutingMigrations` (which
records V13 as applied without re-running its DDL) — rather than hand-editing the history table.

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

## 2026-08-09 — Phase 3 checkpoint 16: MCQ difficulty mix, Bloom pairing, chunk-coverage steering

**What changed:** Batch MCQ generation (`POST /documents/{id}/question-sets`, 10/25/50 counts)
reuses checkpoint 15's `BatchRepairLoop` for the same partial-success contract (keep valid
questions, repair only the malformed subset once, fail only if zero survive — `generated_count`
on `question_sets` can be `< requested_count`). Three numbers/mechanics were designed fresh, same
posture as Phase 2's RRF `k=60`/`0.35` confidence floor, because the master spec's §6.3 MCQ detail
was never transcribed into this repo (see `specs/15-PENDING.md`):

- **Difficulty mix: 40% EASY / 40% MEDIUM / 20% HARD.** Divides evenly at all three allowed
  counts (10→4/4/2, 25→10/10/5, 50→20/20/10).
- **Bloom level, paired to difficulty rather than mixed independently** (one target list, not
  two crossed ratios): EASY→mostly REMEMBER, some UNDERSTAND; MEDIUM→mostly UNDERSTAND, some
  APPLY; HARD→mostly APPLY, some ANALYZE (every 4th question at a given difficulty takes the
  secondary level). `EVALUATE`/`CREATE` are dropped from the Bloom set — they don't map onto an
  objective single-correct-answer MCQ.
- **Chunk-coverage steering** is an explicit per-question target list built into the prompt
  (`"Question 3: EASY / UNDERSTAND, primarily from chunk <id>"`), with anchor chunks spread evenly
  across the document (`(questionIndex * chunkCount) / requestedCount`) rather than a vague
  "spread out" instruction — gives the model and the validator something concrete. Anchor mismatch
  isn't hard-failed (a question citing a neighbouring chunk is fine); only "citation belongs to
  this document" is enforced.
- **Large documents** (no natural "reduce" step for a question set, unlike summary's map-reduce):
  chunks are split into token-budget groups (8000 tokens, headroom above summary's 6000 for the
  chunk manifest + target list), the requested count is split proportionally across groups, each
  group is generated independently, and results are concatenated and trimmed to exactly
  `requestedCount` if rounding overshot.
- **MCQ answers are returned directly** in `GET /question-sets/{id}/questions` (`correctIndex` +
  `explanation`, no server-side reveal gate) — this phase's MCQs are self-study review, not a
  proctored/scored quiz. The frontend hides the answer behind a client-side "reveal" button.
  Phase 4's quiz mode can add a real gate later if scored attempts need one.
- **MCQ batches debit the existing `AI_JOBS` monthly quota flat**, same as every other job type,
  rather than a new weighted metric — matches the precedent that a summary on a 200-page document
  debits the same as one on a 5-page document.

**Why:** These are load-bearing product numbers invented under real constraints, not lifted from
the master spec's actual §6.3. If the missing spec paste ever arrives with different numbers,
treat this whole entry as superseded and re-tune against it.

**What it costs:** Nothing functional yet — CI wiring for the eval harness that will eventually
measure whether this difficulty/Bloom/coverage steering is actually working is deferred to Phase 5
(see `docs/status/phase-3.md`).

**Fixed along the way — `BatchRepairLoop` didn't handle a provider-level call failure:** The MCQ
integration test's first real run hit a genuine live failure: Groq's own `json_object`-mode
validator rejected a 10-question generation outright (`400 json_validate_failed`, empty
`failed_generation` — no content at all to inspect), which `BatchRepairLoop.run()` had no handling
for beyond letting the raw `HttpClientErrorException` propagate as an unretried `HANDLER_ERROR`
job failure. Fixed by catching a non-transient failure on the *first* provider call and spending
the loop's one repair attempt on a plain retry of the identical request (there's no content to
build a targeted per-item repair instruction from); a `TransientJobException` (429/5xx) is
re-thrown untouched so the job engine's own backoff/retry handles it instead, rather than the loop
burning its one internal attempt on something a requeue would fix better. If the retry also fails
at the provider level, the job now fails cleanly with `AI_SCHEMA_INVALID` instead of a raw
provider exception. Confirmed by re-running `McqGenerationIntegrationTest` clean afterward. This
strengthens key points too, since both features share `BatchRepairLoop`.

---

## 2026-08-09 — Phase 3 checkpoint 17: eval harness, Java not Node

**What changed:** Built `eval/documents/` + `eval/answer-keys/` (3 starter documents — DBMS
ACID/normalization, OS process scheduling, networking/OSI — growing toward 15-20 per
`docs/status/phase-3.md`) and a Java harness
(`backend/src/test/java/com/studyflow/eval/{EvalHarnessRunner,EvalDocumentFixtures,EvalReport}.java`)
tagged `@Tag("eval")` and excluded from the default `mvn test` run via a new
`surefire.excludedGroups` POM property (overridable with `-Dsurefire.excludedGroups=` to run it
deliberately). No JS test runner exists in this repo and the backend already owns every piece of
real infrastructure the harness needs, so a Node script was never in the running.

The harness runs the real pipeline (upload → ingest → key points → MCQs → retrieval probes) per
document and computes the 5 metrics named in `specs/08-ai-layer.md`'s "Eval harness" section
against the thresholds proposed in the earlier checkpoint-16 entry — schema pass rate and MCQ
validity from `ai_calls`/persisted `questions` rows, citation groundedness both structurally
(cited chunk id belongs to the document, via the published `ChunkQueryService`, never a direct
repo injection) and via a lexical word-overlap heuristic (≥15% word overlap between citing text
and cited chunk content — approximate, flagged as such, a full semantic judge needs its own LLM
budget this phase doesn't have), and retrieval recall via `RetrievalService` against each answer
key's probes. First real run: `eval/results/baseline.md` — schema pass rate, both citation
metrics, and retrieval recall all 100% on real data; MCQ validity came back 0/0 because Groq
itself rate-limited every MCQ batch in that particular run (see below), not a validity failure.

**Why:** CI wiring for this harness was already deferred to Phase 5 (previous entry). The harness
itself needed to exist this phase regardless, per the spec's own framing ("build alongside MCQs,
not before there's a batch feature to evaluate").

**What it costs:** Metrics are computed by a fresh Java re-implementation of the validation logic
(structural checks, lexical overlap), not a shared library with the production validators in
`McqGenerationService`/`KeyPointExtractionService` — acceptable for an independent regression
check (the whole point is verifying what's actually persisted, not trusting the generator's own
self-report), but worth remembering if the production validation rules change and this harness
needs a matching update.

**Discovered along the way — Groq itself has a tight rate limit on this account, same shape as the
already-documented Voyage constraint:** every MCQ generation job failed in the harness's first
real run (`AI_SCHEMA_INVALID`, both the first call and `BatchRepairLoop`'s one repair attempt hit
`429`) because 3 back-to-back 10-question MCQ batches plus the run's ingestion/key-points calls
exceeded this account's Groq request budget within a couple of minutes. This is not a regression —
`McqGenerationIntegrationTest` (one MCQ call, run in isolation) passes cleanly, matching exactly
the existing Voyage caveat's shape ("each test class passes reliably run on its own with normal
spacing"). `retrieveWithRetry` (a small local retry-with-20s-backoff wrapper in
`EvalHarnessRunner`, since a direct `RetrievalService.retrieve()` call isn't job-queued and gets no
retry from the job engine) was added so a Voyage `429` on a retrieval probe doesn't fail the whole
harness run — retrieval recall (9/9) recovered fully after retries. No equivalent retry exists for
the job-based Groq calls because the job engine's own backoff already retries those; the harness
just observed the account run out of retries within `AiJob.maxAttempts` under this much load.

---

## 2026-08-09 — Phase 3 checkpoint 18: flashcards + SM-2, first mutable row in study/

**What changed:** Flashcard batch generation reuses `BatchRepairLoop`/the same
partition-and-concatenate-for-large-documents pattern as key points (no fixed target count).
Reviews (`POST /flashcards/{id}/review`) are synchronous, not job-queued — pure SM-2 arithmetic,
no LLM call, fits the "Auth, CRUD, listing → synchronous, p95 < 300ms" row in
`specs/01-architecture.md`.

- **SM-2 formulas**: Piotr Wozniak's original 1990 algorithm
  (`easeFactor' = max(1.3, easeFactor + (0.1 - (5-q)*(0.08 + (5-q)*0.02)))`; interval
  1 → 6 → `round(interval * easeFactor)`; `q < 3` resets repetitions to 0 and interval to 1 day),
  implemented verbatim in `Sm2Calculator` — a fresh design call, same posture as the checkpoint 16
  MCQ numbers, because (per `specs/15-PENDING.md` and `docs/status/phase-3.md`'s own note) the
  master spec's SM-2 formulas were never actually transcribed anywhere retrievable in this repo,
  despite the note suggesting they could be "pulled forward verbatim." New cards start at
  `easeFactor=2.5`, `intervalDays=0`, `repetitions=0`, `dueAt=now()` (immediately due).
  `Sm2Calculator` is a pure static function (no DB/HTTP dependency) with its own plain-JUnit
  `Sm2CalculatorTest` (10 cases) — no Spring context needed.
- **`dueAt` is computed in the owner's timezone** (`users.timezone`, already on `User`, default
  `Asia/Kolkata`) as calendar days via `ZonedDateTime.plusDays(...)`, not a naive `now + N*24h` —
  avoids the "due" wall-clock time drifting forward on every review. Not truncated to local
  midnight (a common SRS refinement batching a day's due cards together) — nothing asked for it;
  an easy add later, not a silent gap.
- **UI collapses SM-2's 0-5 quality scale to 4 buttons** (Again/Hard/Good/Easy → q=0/3/4/5,
  Anki-style) — a literal 6-button 0-5 rating is unusual in real flashcard UIs. The backend still
  accepts/validates the full 0-5 range (`FlashcardReviewRequest`'s `@Min(0) @Max(5)`), so nothing
  structurally blocks a richer UI later.
- **`flashcards` is the first mutable row in `study/`** — every other batch-study table
  (summaries, key_points, question_sets/questions) is insert-only. Added `@Version` (JPA
  optimistic locking, migration `V17__flashcards.sql`'s `version` column) so a stale concurrent
  review fails loudly (`ObjectOptimisticLockingFailureException`) instead of silently overwriting
  newer SM-2 state — same lesson as the Phase 1/2 concurrent-refresh-token bug. Verified with a
  deterministic test (`FlashcardGenerationIntegrationTest.aStaleReviewNeverSilentlyOverwritesANewerOne`):
  two independent reads of the same card, one save succeeds, the second (stale) save is confirmed
  to throw rather than silently losing the first review — not a timing-dependent concurrent-HTTP
  test, which would've been flaky and inconclusive given real network/connection-pool interleaving.
- **`GET /flashcards/due` is a plain top-N-by-`due_at` query, not cursor-paginated** — deviates
  from the plan's initial `?limit=&cursor=` sketch. A due-now queue is inherently dynamic (each
  review reschedules the very card that was just shown), so paging through a fixed snapshot the
  way `GET /jobs` does is the wrong model; "the next N due, freshly queried" is more correct.

**Why:** SM-2 and the timezone-aware due-date math are load-bearing product numbers invented under
real constraints, not lifted from the master spec's actual formulas. If the missing spec paste
ever arrives with different formulas, treat this whole entry as superseded and re-tune against it.

**What it costs:** Nothing functional — `FlashcardGenerationIntegrationTest` (3 tests, including
the optimistic-lock regression test) and `Sm2CalculatorTest` (10 tests) both pass against real
Postgres/Groq. Running the full Phase 3 real-infra suite back-to-back in one `mvn test` invocation
can still intermittently show `McqGenerationIntegrationTest`/`KeyPointGenerationIntegrationTest`
failures from the same Voyage/Groq rate caps already documented above — not a regression, each
test class passes cleanly run on its own (confirmed this session).

---

## 2026-08-09 — Local environment: Postgres 16 service was shadowing the project's Postgres 15

**What changed:** Nothing in application code. While setting up to run checkpoint 16's integration
test, found that `postgresql@16` (a Homebrew service unrelated to this project) was bound to port
5432, so `postgresql@15` — the version this project's datasource, `docs/DECISIONS.md`, and
`CLAUDE.md` all assume — was crash-looping in the background ("could not bind IPv4/IPv6 address:
Address already in use"). The app was silently connecting to an empty Postgres 16 instance with
no `studyflow_test`/`studyflow_dev` databases and no `pgvector` extension built for it, rather than
the real, already-migrated Postgres 15 instance. Fixed by `brew services stop postgresql@16` +
`brew services start postgresql@15` — the pre-existing `studyflow_test` database (migrated through
V15 by checkpoint 15) was intact and unaffected once Postgres 15 was reachable again.

**Why:** Worth logging because the symptom (`FATAL: database "studyflow_test" does not exist`,
then `ERROR: type "vector" does not exist`) looks like a code or migration bug at first glance but
is purely a local service-port collision. If this machine has other Postgres versions installed
via Homebrew, re-check `brew services list` before assuming a schema problem.

**What it costs:** Nothing — no data was lost; the Postgres 16 instance's (empty, unrelated)
`studyflow_test`/`studyflow_dev` databases created during diagnosis were left in place on the now-
stopped `postgresql@16` service rather than deleted, since that service may belong to another
project on this machine.

---

## 2026-08-08 — Testcontainers → local Postgres for integration tests

**What changed:** Integration tests run against a real, dedicated local `studyflow_test`
database (Homebrew Postgres 15) instead of a Testcontainers-managed ephemeral Postgres.

**Why:** No Docker is installed in this environment (verified: `docker` command not found,
no daemon reachable). A local Postgres 15 server is already running and reachable.

**What it costs:** Tests are not hermetic across machines/CI the way Testcontainers would be —
`studyflow_test` must exist and be migrated before tests run, and test isolation between runs
depends on a `DatabaseCleaner` truncating tables rather than a fresh container per run. The spec's
actual intent (real Postgres, not mocks) is preserved.

**Update 2026-08-11: superseded.** Docker is now available — see the "Testcontainers lands" entry
above. It genuinely was closer to "a config change" than "a rewrite," though not quite as trivial
as originally hoped (a JUnit global-extension singleton container plus a real, previously-hidden
migration-ordering bug it surfaced) — see that entry for the details.

---

## 2026-08-08 — Cloudinary → local disk storage

**What changed:** File storage uses a `StorageProvider` interface with a `LocalDiskStorageProvider`
implementation writing to `STORAGE_LOCAL_ROOT`, instead of Cloudinary (`resource_type=raw`,
`type=authenticated`, signed URLs).

**Why:** No Cloudinary account/credentials available for this build.

**What it costs:** No CDN, no signed time-limited delivery URLs, no offloading of storage from the
app server. Upload is also a direct multipart `POST /documents` instead of the spec's presigned
upload-intent + direct-to-Cloudinary two-step (see next entry). Swapping in a
`CloudinaryStorageProvider` is still additive if this changes, but **update 2026-08-11: permanent,
by user decision, not "revisit when an account exists."** No Cloudinary integration is planned.

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
documented fallback path, not a lesser mode).

**Update 2026-08-11: superseded.** An Upstash account now exists — see the "Redis L2 login-lock
durability" entry above for the first slice built on it (login lockouts only; the AI-job/upload
sliding-window buckets and SSE job-progress streaming described in `specs/06-rate-limiting.md`
are still not built, but they're no longer blocked on an account, just not-yet-done).

---

## 2026-08-08 — Razorpay/billing → deferred entirely

**What changed:** No `plans`/`subscriptions` tables, no Razorpay integration. A single hardcoded
limit set in `application.yml` stands in for plan-tiered quotas.

**Why:** No Razorpay account for this build; billing has no dependents in Phase 1's feature set.

**What it costs:** No real monetization path yet, no plan-tiered job priority. The
`usage_counters` enforcement mechanism itself (atomic upsert at enqueue time) is still real, so
introducing real plans later changes where the limit number comes from, not the enforcement code
path.

**Update 2026-08-11: permanent, by user decision, not "revisit in Phase 6."** No Razorpay
integration is planned — Phase 6 (Billing) is dropped from the roadmap.

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
