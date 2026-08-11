# CLAUDE.md — StudyFlow AI

Read this before touching any feature code. Full spec detail lives in [`specs/`](specs/) (start
at [`specs/README.md`](specs/README.md)); this file is the operating contract, not the spec
itself.

## Tech stack

- **Backend:** Spring Boot 4.0.7 (Spring Framework 7, Spring Security 7, Hibernate 7 — see
  `docs/DECISIONS.md`), Java 21 language level (JDK 24 installed locally, `--release 21` keeps
  bytecode portable), Maven (no Gradle in this environment).
- **DB:** Postgres (local Homebrew Postgres 15 for now; Neon planned for prod — see
  `docs/DECISIONS.md`) + `pgvector`. Flyway migrations only, `ddl-auto=validate` always.
- **AI:** Groq (chat, OpenAI-compatible API) + Voyage AI (embeddings, `voyage-4-lite`). Model IDs
  are config, never hardcoded — see [`specs/08-ai-layer.md`](specs/08-ai-layer.md).
- **Frontend:** React 19 + Vite + TypeScript strict, TanStack Query for server state.
- **Storage:** local disk this phase (`StorageProvider` interface; Cloudinary planned — see
  [`specs/05-library-and-storage.md`](specs/05-library-and-storage.md)).
- **Testing:** real local Postgres for integration tests (Testcontainers deferred — no Docker in
  this environment; see `docs/DECISIONS.md`).

## Module boundaries

Feature-first backend packages under `com.studyflow`: `common`, `identity`, `library`, `rag`,
`ai`, `study`, `jobs`, `tutor` (built so far); `billing`, `planner`, `exports`, `admin` (later
phases). Full detail: [`specs/01-architecture.md`](specs/01-architecture.md). **Rule:** cross-
feature access only through a published service interface — never inject one feature's repository
into another feature's service.

## Error model

RFC 9457 `application/problem+json` everywhere, always. `code` is the stable machine-readable
field the frontend switches on — never render `detail` to a user. Full code table:
[`specs/03-api-and-errors.md`](specs/03-api-and-errors.md).

## Naming conventions

- Owner-scoped repositories expose `findByIdAndOwnerId(id, ownerId)` — never a bare `findById`
  call from outside the repository's own package. Enforced by `ArchitectureTest` (ArchUnit).
- DB tables/columns: `snake_case`. Java: standard camelCase. JSON over the wire: camelCase.
- Flyway migrations: `V{n}__{description}.sql`, one logical unit of work per migration, never a
  giant catch-all.
- Prompt templates: `resources/prompts/{purpose}/v{n}.md` + a manifest — never a string literal
  in a service class.

## Never do this

- Never set `ddl-auto` to anything but `validate` outside tests. Schema changes go through
  Flyway, always, from commit one.
- Never write a repository query not scoped by `owner_id`. There is no "find by id" — only "find
  by id **and** owner."
- Never call an LLM inside a request thread a browser is waiting on, except streamed chat (tutor
  — runs off the request thread via a dedicated pool + `SseEmitter`, never blocking the servlet
  thread). Long AI work is an async job.
- Never trust a model's JSON. Validate against a schema, then validate semantics, with one repair
  call on failure, and degrade gracefully rather than failing an entire batch.
- Never interpolate uploaded document text into a system prompt — it goes in a delimited
  user-role message; the system prompt says delimited content is data, not instructions.
- Never log document text, chat messages, JWTs, or API keys.
- Never store secrets in `application.yml`. Environment variables only, resolved at boot,
  fail fast if absent.
- Never leave a bare `catch (Exception e) { e.printStackTrace(); }` anywhere.
- Never write a method body that returns `null`, a `TODO` comment, or a mock response as a
  stand-in for a real implementation. If it's out of scope, it goes in `docs/DECISIONS.md` and
  `specs/ROADMAP.md` as an explicit deferral, not a silent stub in code.

## The verification loop

For every unit of work: write the migration → write the integration test (must fail first) →
implement until it passes against real Postgres → hit the endpoint manually via
`docs/http/slice1.http`, paste the real response into the commit message (redact secrets/tokens
first) → only then move on.

## Deviations from the spec

Every deviation is dated and justified in [`docs/DECISIONS.md`](docs/DECISIONS.md). Silent
deviation is the failure mode this guards against — documented deviation is engineering.

## Build Log

Phase detail and sequencing: [`specs/ROADMAP.md`](specs/ROADMAP.md).

| Phase | Status | Date | Exit-criteria evidence |
|---|---|---|---|
| 0 — Session 0 (this doc, specs/, scaffolds) | Done | 2026-08-08 | `specs/` 17 files; `mvn compile` clean; `/actuator/health` → 200; `npm run build` clean. |
| 1 — Auth → Upload → Ingestion → Async Summary | Done | 2026-08-08 | See below. |
| 2 — Tutor chat + retrieval | Done | 2026-08-08 | See below. |
| 3 — Batch study generation (MCQs/flashcards) + eval harness | Done | 2026-08-09 | See below. |
| 4 — Quizzes | Done | 2026-08-10 | See below. |
| 5 — Infra hardening (Redis/Testcontainers/observability; Cloudinary dropped) | In progress | 2026-08-11 | See below. |
| 6 — Billing | Dropped (user decision) | 2026-08-11 | No Razorpay — see `docs/DECISIONS.md`. |
| 7 — Planner, exports, admin | In progress (planner done) | 2026-08-11 | See below. |

### Phase 1 exit-criteria evidence

Full backend test suite green against real Postgres 15 + pgvector, real Groq, real Voyage AI
(`cd backend && set -a && source .env && set +a && ./mvnw test` — 20 tests, 0 failures, including
`ArchitectureTest`'s 5 tenancy rules and 3 integration tests that make real external API calls).
Frontend: `tsc -b` strict-mode clean, `npm run build` clean.

End-to-end, driven through the real browser UI (Playwright against the running dev servers, not
just curl) — register → login → upload a real `.md` file → watched ingestion reach `READY` →
clicked *Generate summary* → watched a real Groq-generated (`openai/gpt-oss-120b`), cited summary
appear with a working "Sourced from your notes" rail → confirmed the document shows *Ready* back
on the library list. No mocked step anywhere in the path — see the checkpoint commits for the
redacted request/response evidence at each stage:

- `db141f0` DB migration + job engine core (claim/dispatch/sweep, concurrency-tested)
- `e938657` storage + upload (magic-byte sniffing, sha256 dedup)
- `a61b4f7` ingestion pipeline + real Voyage embeddings (`vector(1024)`, dimension confirmed live)
- `47ca568` Groq AI provider adapter + real cited summary generation
- `6288aae` ArchUnit tenancy test (verified it actually catches a violation, not just that it passes)
- `80ee948` frontend + UI states + accessibility pass (keyboard nav, focus rings, error states)

Manual verification collection: [`docs/http/slice1.http`](../docs/http/slice1.http).

### Phase 2 exit-criteria evidence

Backend test suite against real Postgres 15 + pgvector, real Groq (including streamed chat
completions), real Voyage AI:

```
cd backend && set -a && source .env && set +a && ./mvnw test
```

`ArchitectureTest` (16 ArchUnit rules total, all tenancy checks across 8 owner-scoped
repositories; Phase 1 had 5 owner-scoped repositories) and `GroqAiProviderStreamingTest` (SSE parsing +
a `<think>` tag deliberately split across stream chunks, against a fake server) pass
deterministically. `RetrievalServiceIntegrationTest` and `TutorChatIntegrationTest` — real hybrid
retrieval (vector + lexical + RRF fusion + neighbour expansion) and a real streamed, cited tutor
reply with the confidence-floor/explain-beyond-notes grounding contract exercised end to end — each
pass cleanly run standalone; this account's Voyage tier is hard-capped at 3 requests/minute (no
payment method on file, confirmed live), so an unbroken full-suite run can intermittently show a
transient rate-limit failure that isn't a code defect — see `docs/DECISIONS.md`.

End-to-end, driven through the real browser UI — register → login → upload a document → open the
Tutor page → asked a question covered by the notes → watched a real, token-streamed Groq reply
arrive with in-range citations and a "From your notes" grounding badge → toggled "explain beyond
my notes" on an unrelated question → watched a "Beyond your notes" badge instead. No mocked step
anywhere in the path. This walkthrough found and fixed two real bugs (a duplicate citation-rail
render, and a pre-existing Phase 1 concurrent-`/auth/refresh` 500 that blocked verification itself)
— see `docs/status/phase-2.md` and `docs/DECISIONS.md` for full writeups.

Frontend: `tsc -b` strict-mode clean, `oxlint` clean, `npm run build` clean.

Manual verification collection: [`docs/http/slice2.http`](docs/http/slice2.http).

### Phase 3 exit-criteria evidence

Backend test suite against real Postgres 15 + pgvector, real Groq, real Voyage AI, across all four
checkpoints (key points, MCQs, the eval harness, flashcards+SM-2):

```
cd backend && set -a && source .env && set +a && ./mvnw test -Dtest=ArchitectureTest,GroqAiProviderStreamingTest,Sm2CalculatorTest,KeyPointGenerationIntegrationTest,McqGenerationIntegrationTest,FlashcardGenerationIntegrationTest
```

`ArchitectureTest` (22 tenancy rules, up from 16 at the end of Phase 2) and
`GroqAiProviderStreamingTest` pass deterministically. `Sm2CalculatorTest` (10 cases, pure function,
no Spring context) passes deterministically. `KeyPointGenerationIntegrationTest`,
`McqGenerationIntegrationTest`, and `FlashcardGenerationIntegrationTest` — real
upload→ingest→generate→persist round trips, including a deterministic optimistic-locking
regression test for flashcard reviews — each pass cleanly run standalone; this account's Voyage
tier is hard-capped at 3 RPM and Groq showed the same shape of tier limit under sustained load
(confirmed live during the eval harness's first run), so an unbroken run of all three real-infra
test classes back-to-back can intermittently show a transient rate-limit failure that isn't a code
defect — see `docs/DECISIONS.md`, same caveat already documented for Phase 2.

The eval harness (`com.studyflow.eval.EvalHarnessRunner`, excluded from default `mvn test`) ran
its first real baseline over 3 documents: schema pass rate, both citation-groundedness metrics,
and retrieval recall all 100% on real data — see `eval/results/baseline.md` for the full report
and the run's own rate-limit caveat.

Frontend: `tsc -b` strict-mode clean, `oxlint` clean (no new warnings), `npm run build` clean —
covering the new Key Points, MCQ, and Flashcards pages, the `.partial-banner` component state, and
the flip-card review UI. **Not done this session:** a live browser walkthrough (the Phase 1/2
evidence bar) of the MCQ and Flashcards pages specifically — no browser-automation tool was
available in this session to drive one, so this is flagged explicitly per this file's own rule
rather than claimed. Key Points' page did get a real browser walkthrough in an earlier session
(see `docs/status/phase-3.md`'s checkpoint 15 note). Recommend a Playwright pass over
`/documents/:id/mcqs` and `/documents/:id/flashcards` before treating Phase 3's UI as fully proven,
same rigor as Phase 1/2's sign-off.

Full writeup, checkpoint-by-checkpoint: [`docs/status/phase-3.md`](docs/status/phase-3.md).
Every invented product number (difficulty mix, Bloom pairing, chunk-coverage steering, SM-2
constants, eval thresholds) is logged with rationale in
[`docs/DECISIONS.md`](docs/DECISIONS.md).

### Phase 4 exit-criteria evidence

Backend test suite against real Postgres 15 + real Groq:

```
cd backend && set -a && source .env && set +a && ./mvnw test -Dtest=ArchitectureTest,QuizGenerationIntegrationTest
```

`ArchitectureTest` (28 tenancy rules, up from 22 at the end of Phase 3 — 6 new for
`QuizRepository`/`QuizAttemptRepository`/`QuizAnswerRepository`) passes deterministically.
`QuizGenerationIntegrationTest` (6 tests total: one exercising EXAM negative-marking,
answer-key-withheld-until-submit, and the clear-an-answer path together; one each for PRACTICE
no-negative-marking and REVISION untimed with immediate feedback; one deterministic EXAM-expiry
test that backdates `deadline_at` directly rather than a real `Thread.sleep`; plus 2 validation
tests) — every test method confirmed passing individually with normal spacing; this account's Groq
tier showed the same sustained rate-limit ceiling already documented for Phase 2/3 under this
session's cumulative real-API load, not a code defect (see `docs/DECISIONS.md`).

Frontend: `tsc -b` strict-mode clean, `oxlint` clean (no new warnings), `npm run build` clean.

End-to-end, driven through the real browser UI (Playwright against the running dev servers) —
register → login → upload a real `.md` file → watched ingestion reach `READY` → built a real
EXAM-mode quiz (real Groq generation) → started a timed attempt, confirmed the server-issued
countdown rendered and the answer key was absent both from the DOM and from a direct
`GET /quiz-attempts/{id}/result` call while in progress (`409 QUIZ_ATTEMPT_NOT_IN_PROGRESS`) →
answered questions via the OMR-bubble control → submitted → the result page showed a real
negative-marked score (`-0.75 / 6`, matching `0×1 - 3×0.25` exactly) with the answer key,
explanations, and citations correctly revealed only at that point. Separately verified REVISION
mode gives immediate right/wrong feedback with a real generated explanation, and renders no timer.
No mocked step anywhere in either path.

Full writeup, including the two documented external-infra caveats hit along the way (Groq's
sustained rate ceiling under this session's load, and unrelated pre-existing migration drift on
the local `studyflow_dev` database): [`docs/status/phase-4.md`](docs/status/phase-4.md). Every
invented product number (mode semantics, the 90s/question timing formula, the −0.25 negative-
marking fraction, the scoring formula) is logged with rationale in
[`docs/DECISIONS.md`](docs/DECISIONS.md).

### Phase 5 exit-criteria evidence (first slice: DOCX/PPTX ingestion + login rate limiting)

Phase 5 bundles five tracks (Cloudinary, Redis, Testcontainers, observability, DOCX/PPTX parsing);
three remain blocked on external accounts/tools not yet available in this environment (Cloudinary
credentials, an Upstash Redis account or local Redis, Docker for Testcontainers) and observability
needs a stack decision. The two tracks needing no external accounts landed first:

```
cd backend && set -a && source .env && set +a \
  && ./mvnw test -Dtest=ArchitectureTest,DocxDocumentParserTest,PptxDocumentParserTest,\
DocumentUploadIntegrationTest,PptxIngestionIntegrationTest,LoginRateLimiterTest,\
LoginRateLimitIntegrationTest,AuthFlowIntegrationTest
```

`ArchitectureTest` stays 28/28 (neither checkpoint adds an owner-scoped repository).
`DocxDocumentParserTest`/`PptxDocumentParserTest` (7 tests, plain JUnit, no Spring context) pass
deterministically. `DocumentUploadIntegrationTest` (7 tests, 3 new DOCX/PPTX/renamed-zip cases) and
`PptxIngestionIntegrationTest` (1 test, real Postgres + real Voyage embeddings — DOCX not
duplicated here since its pipeline shape is already proven by the existing TXT/MD end-to-end test)
pass against real infra. `LoginRateLimiterTest` (5 tests, deterministic via an injected `Clock`,
no real waiting) and `LoginRateLimitIntegrationTest` (2 tests, real Postgres, real HTTP) pass, and
the pre-existing `AuthFlowIntegrationTest` (6 tests) confirmed no regression.

Frontend: `tsc -b` strict-mode clean, `oxlint` passes with one pre-existing unrelated warning
(same as every prior phase — not added this phase), `npm run build` clean.

Manually verified against a running local instance (pointed at `studyflow_test`, not
`studyflow_dev` — same pre-existing migration-drift workaround as Phase 4, see
`docs/DECISIONS.md`): uploaded a real `.docx` and a real `.pptx` via `curl`, both reached `READY`
with the correct `fileType`/`mimeType`/`charCount`. Separately, 5 real failed logins against a real
registered account followed by a 6th returned:

```text
HTTP/1.1 429
Retry-After: 60
{"status":429,"title":"RATE_LIMITED","code":"RATE_LIMITED","retryAfterSeconds":60}
```

Every invented number (POI dependency version, the 200 MiB zip-entry cap, the 300-slide cap, the
exponential-lockout formula, the 2h idle-eviction window) is logged with rationale in
[`docs/DECISIONS.md`](docs/DECISIONS.md).

### Phase 5 exit-criteria evidence (second slice: Redis L2 login-lock durability)

Cloudinary and Razorpay are permanently out (user decision, 2026-08-11 — see
`docs/DECISIONS.md`); Redis (Upstash) and Docker are now available instead. First Redis slice: L2
durably backstops the L1 login-lockout so a restart can't reset an active lock — see
`docs/DECISIONS.md` for the design (REST API, not a TCP client; SHA-256 keys; fail-open).

```
cd backend && set -a && source .env && set +a \
  && ./mvnw test -Dtest=LoginRateLimiterTest,LoginRateLimitIntegrationTest,ArchitectureTest,AuthFlowIntegrationTest
```

`LoginRateLimiterTest` (5 tests, `Clock` + a no-op `LoginLockStore` stub, still fully deterministic
and network-free) and `LoginRateLimitIntegrationTest` (3 tests, real Postgres + real Upstash —
including `aLockKnownOnlyToRedisStillBlocksLogin`, which seeds L2 directly to prove the exact
post-restart scenario without actually restarting the app) both pass. `ArchitectureTest` unaffected
at 32/32. `AuthFlowIntegrationTest` (6 tests) confirms no regression on the login path.

### Phase 5 exit-criteria evidence (third slice: Testcontainers)

Docker is available now (user decision, 2026-08-11); integration tests run against a real,
Testcontainers-managed `pgvector/pgvector:pg15` container instead of the shared local Homebrew
Postgres — one container per JVM/surefire fork, via a JUnit5 global extension, so none of the 16
existing `@SpringBootTest` classes needed editing. See `docs/DECISIONS.md` for the design and a
real, previously-hidden migration-ordering bug (V7 uses the `vector` type before V10 creates the
extension) this surfaced and fixed.

```
cd backend && set -a && source .env && set +a && ./mvnw test
```

101/109 passed in one full run. The 8 failures: the already-documented Groq/Voyage rate-limit
shape (7, same caveat as every prior phase) plus one `JobDispatcherIntegrationTest` timing flake
under full-suite load — confirmed 4/4 passing standalone immediately after, not a Testcontainers
regression.

### Phase 7 exit-criteria evidence (first slice: study planner)

Started ahead of finishing Phase 5's remaining tracks / Phase 6 — both blocked on external
credentials (Cloudinary, Redis, Testcontainers, Razorpay); the planner needs none. See
`docs/DECISIONS.md` for why plan build is synchronous rather than the async job
`specs/01-architecture.md` originally classified it as.

```
cd backend && set -a && source .env && set +a \
  && ./mvnw test -Dtest=ArchitectureTest,StudySessionSchedulerTest,StudyPlanIntegrationTest
```

`ArchitectureTest` (32/32, up from 28 — 4 new for `StudyPlanRepository`/`StudySessionRepository`)
and `StudySessionSchedulerTest` (5 cases, pure function, no Spring context) pass deterministically.
`StudyPlanIntegrationTest` (2 tests: full create→list→get→`.ics`-export round trip, not-found) —
real Postgres, and deliberately **no Groq/Voyage call anywhere in the feature** (plan build is pure
scheduling arithmetic), so this is the first study-feature integration test with zero real-API
rate-limit fragility.

Frontend: `tsc -b` strict-mode clean, `oxlint` clean (no new warnings), `npm run build` clean.

Full writeup: [`docs/status/phase-7.md`](docs/status/phase-7.md). Every invented number (the
`{21,14,10,7,5,3,2,1,0}` days-before-exam cadence) is logged with rationale in
[`docs/DECISIONS.md`](docs/DECISIONS.md). Exports and the admin panel are not started.

Update this table at the end of every phase with real evidence (a command output or passing test),
not a checkmark.
