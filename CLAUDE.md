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
`ai`, `study`, `jobs` (this phase); `billing`, `tutor`, `planner`, `exports`, `admin` (later
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
- Never call an LLM inside a request thread a browser is waiting on, except streamed chat
  (deferred). Long AI work is an async job.
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
| 0 — Session 0 (this doc, specs/, scaffolds) | In progress | 2026-08-08 | — |
| 1 — Auth → Upload → Ingestion → Async Summary | Not started | — | — |
| 2 — Tutor chat + retrieval | Not started | — | — |
| 3 — Batch study generation (MCQs/flashcards) + eval harness | Not started | — | — |
| 4 — Quizzes | Not started | — | — |
| 5 — Infra hardening (Cloudinary/Redis/Testcontainers/observability) | Not started | — | — |
| 6 — Billing | Not started | — | — |
| 7 — Planner, exports, admin | Not started | — | — |

Update this table at the end of every phase with real evidence (a command output or passing test),
not a checkmark.
