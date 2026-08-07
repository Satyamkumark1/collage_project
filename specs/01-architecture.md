# Architecture

## Topology

```
Browser (React 19 SPA)
   │  HTTPS/JSON  ·  SSE for streams (deferred)
   ▼
Spring Boot 3.x monolith
   ├─ web layer      controllers, DTO validation, auth filter, rate limiter
   ├─ domain layer    services, policy checks, quota enforcement
   ├─ ai layer         provider adapter, prompt registry, schema validation, cost ledger
   ├─ rag layer        parse → chunk → embed → store (retrieval deferred)
   ├─ job layer        DB-backed queue + in-process worker pool
   └─ data layer       JPA repositories, Flyway migrations
   │
   ├── Postgres + pgvector   (relational + vectors, one DB — local for now, Neon later)
   ├── Groq                    (chat inference)
   ├── Voyage AI                (embeddings)
   ├── Local disk storage       (Cloudinary later)
   └── Redis, Cloudinary, Razorpay — deferred, see ROADMAP.md
```

**One deployable unit.** No microservices. The worker runs as a thread pool inside the same JVM,
behind an interface, so it can be split into a separate service later by changing config, not
rewriting code.

## The sync/async boundary — the central design decision

| Path | Mode | Budget |
|---|---|---|
| Auth, CRUD, listing | Synchronous | p95 < 300ms |
| Tutor chat (deferred) | Streaming SSE | First token < 2.5s |
| Summary, key points, MCQs, flashcards, quiz build, study plan | **Async job** | Enqueue < 150ms; job completes 20–180s |
| Document ingestion (parse → chunk → embed) | **Async job** | 10–90s depending on page count |

Anything on the async path returns `202 Accepted` with a `jobId` immediately. Never block a
browser-waited request thread on an LLM call.

## Job model summary

Full detail in [07-jobs-and-async.md](07-jobs-and-async.md). Table `ai_jobs`, worker claims with
`SELECT ... FOR UPDATE SKIP LOCKED` — this is what makes it safe to eventually run two instances.

## Package structure (backend)

Feature-first, not layer-first. One package per bounded feature, each with its own `web/`,
`service/`, `domain/`, `repo/`, `dto/` as needed.

```
com.studyflow
├── common/        errors, pagination, security primitives, clock, ids, config
├── identity/      users, auth, tokens, sessions, roles
├── billing/       plans, subscriptions, quotas, webhooks, usage meter        [deferred]
├── library/       documents, upload, storage adapter, parsing, ingestion jobs
├── ai/            provider adapter, prompt registry, schema validation, cost ledger
├── rag/           chunking, embedding, vector store, (retrieval — deferred)
├── study/         summaries (this phase); key points/flashcards/mcqs/quizzes [deferred]
├── tutor/         conversations, messages, streaming                          [deferred]
├── planner/       study plans, sessions, calendar export                      [deferred]
├── exports/       PDF/DOCX rendering                                          [deferred]
├── jobs/          queue, worker pool, sweeper, progress
└── admin/         admin-only read models and moderation actions               [deferred]
```

Cross-feature access goes through a published service interface only. No repository from one
feature is ever injected into another feature's service.
