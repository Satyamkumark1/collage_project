# Eval harness — baseline run

Run: 2026-08-09, `EvalHarnessRunner.runEvalSuite`, against real Postgres 15 + real Groq + real
Voyage AI (`cd backend && set -a && source .env && set +a && ./mvnw test -Dtest=EvalHarnessRunner
-Dsurefire.excludedGroups=`). 3 documents (`eval/documents/`), 292.6s wall-clock, `BUILD SUCCESS`.

| Metric | Result | Proposed threshold |
|---|---|---|
| Schema pass rate | 100.0% (3/3) | >= 95% |
| MCQ validity (persisted, re-checked) | 0.0% (0/0) — see note below | 100% |
| Citation groundedness (structural) | 100.0% (45/45) | 100% |
| Citation groundedness (lexical overlap, approximate) | 100.0% (45/45) | >= 70% |
| Retrieval recall | 100.0% (9/9) | >= 80% |
| Job latency (p95) | 30223ms | <= 180000ms |

## Notes from this run

All three MCQ generation jobs failed this run (`AI_SCHEMA_INVALID`, both the initial call and
`BatchRepairLoop`'s one repair attempt hit `429`) — **this is Groq rate-limiting this account, not
a code defect.** Running 3 back-to-back 10-question MCQ batches within a couple of minutes, on top
of the same account's ingestion/key-points Groq calls, exceeded this tier's request budget. This
is the same class of constraint already documented for Voyage AI (`docs/DECISIONS.md`'s "Voyage AI
account has no payment method" entry) — Groq apparently has an analogous tier limit on this
account, not previously exercised this hard in a single run before checkpoint 17. Confirmed not a
regression: `McqGenerationIntegrationTest` (a single MCQ generation call, run in isolation) passes
cleanly — see `docs/status/phase-3.md`.

Because of that, this baseline's "MCQ validity" and "citation groundedness" numbers are drawn
almost entirely from key points (all 3 of which succeeded) rather than a mix of both features.
Retrieval recall (9/9) and schema pass rate (3/3, counting only calls that actually returned
content) still reflect real signal. Re-run with wider spacing between documents once this
account's Groq tier is confirmed/upgraded to get an MCQ-inclusive baseline.

## How to reproduce

```
cd backend
set -a && source .env && set +a
./mvnw test -Dtest=EvalHarnessRunner -Dsurefire.excludedGroups=
```

The harness is excluded from the default `mvn test` run (see `pom.xml`'s
`surefire.excludedGroups` property) — it hits real Groq/Voyage in a loop over every document in
`eval/documents/` and is meant to be run deliberately, not on every build.
