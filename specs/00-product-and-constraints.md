# Product Definition & Hard Constraints

## What StudyFlow AI is

Turns a student's own study material into active-recall practice — summaries, key points, MCQs,
flashcards, timed quizzes, a study plan, and a tutor that answers **only from their uploaded
notes, with citations**.

**Primary user.** Indian undergraduate students (BCA/BTech/BCom/BSc), semester exams and
placement prep, studying from PDFs of unit notes, professor slide decks, and scanned-to-text
textbook chapters. Usage is bursty: near-zero for six weeks, then a spike in the 72 hours before
an exam. Design for the spike, not the average.

**The one thing that must be true.** If the tutor answers something not in the uploaded notes, or
an MCQ has a wrong "correct" answer, the product is worthless. Grounding and answer-validity are
the product, not a feature layered on top. Every architectural decision serves that.

**Positioning against a generic chatbot.** A student can already paste notes into free ChatGPT.
What they can't do there: keep a persistent, searchable library of their own material; generate
50 validated MCQs with an answer key in one action; get a scored, timed quiz with a result
breakdown; see exactly which page of their own notes an answer came from. Build those four things
well.

## Hard constraints the original brief got wrong

These were caught before architecture began — each one would have killed the build if discovered
late.

| # | Wrong assumption | Reality | Required action |
|---|---|---|---|
| 1 | Groq provides embeddings for RAG | Groq serves chat/completion inference only, no embeddings endpoint | Pick a separate embedding provider — see [09-rag.md](09-rag.md) §Embeddings and [`/docs/DECISIONS.md`](../docs/DECISIONS.md) for the choice made (Voyage AI) |
| 2 | Long AI generation over plain HTTP | Generating 50 MCQs takes 40–120s; Render/Vercel/browsers time out well before that | Async job model — [07-jobs-and-async.md](07-jobs-and-async.md), non-negotiable |
| 3 | Render free tier is fine | Free web services sleep after ~15 min idle; JVM cold start 30–60s | Always-on paid instance in prod (deferred — this build runs locally for now) |
| 4 | JWT + CSRF together | Bearer tokens aren't CSRF-vulnerable; cookie auth is. Doing both naively means a broken login or false security | Pick one model — [04-identity-and-security.md](04-identity-and-security.md) |
| 5 | Neon connection limits | Pooled Neon runs PgBouncer in transaction mode — server-side prepared statements break; migrations must not run through the pooler | Two datasources in prod (pooled app / direct Flyway) — not applicable to this build's local Postgres, noted for when Neon is wired in |
| 6 | Upstash free tier for rate limiting | A per-request token-bucket check burns 2–4 commands; 500K/month disappears fast under exam-week load | Local in-memory L1 + Redis L2 — deferred to a later phase, see [06-rate-limiting.md](06-rate-limiting.md) |
| 7 | Cloudinary for PDFs/DOCX | It's an image/video CDN; raw uploads default to public URLs | `resource_type=raw`, `type=authenticated`, signed URLs — deferred, local disk storage for now, see [05-library-and-storage.md](05-library-and-storage.md) |
| 8 | Scanned PDFs "just work" | A photographed textbook page extracts to zero text; pipeline would silently produce empty summaries | Detect extracted-chars-per-page; fail the upload with an actionable error. No OCR in v1 |
| 9 | DeepSeek R1-distill output | Reasoning models emit `<think>` blocks; rendered raw, students see the model's scratchpad | Strip reasoning segments in the provider adapter, never downstream |
| 10 | "Students" as a plain user class | Under DPDP Act 2023, under-18 users need verifiable parental consent; behavioural tracking/targeted ads at them are prohibited. Many BCA first-years are 17 | Age gate at signup — [04-identity-and-security.md](04-identity-and-security.md) §DPDP age gate |

## What's in scope right now

See [ROADMAP.md](ROADMAP.md) for what's actually being built vs. deferred at this point in time.
