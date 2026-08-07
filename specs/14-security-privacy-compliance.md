# Security, Privacy, Compliance

> **PARTIAL.** The pasted master spec cuts off mid-§12, mid-sentence, at "SSRF via…". Everything
> below is what was actually provided. See [15-PENDING.md](15-PENDING.md) for what's missing —
> nothing here invents content to complete the cut-off list.

## §12.1 Threat model to actually defend (as provided, verbatim scope)

- IDOR on every document/summary/quiz/message endpoint — the #1 risk. Addressed by the tenancy
  enforcement rule in [02-data-model.md](02-data-model.md) (owner-scoped repositories, ArchUnit
  test).
- Prompt injection via uploads — addressed in [08-ai-layer.md](08-ai-layer.md) §Prompt-injection
  defence.
- Quiz answer-key leakage — not yet applicable, quizzes are deferred (see
  [10-study-features.md](10-study-features.md)); when built, correct answers/explanations must
  never be sent to the client before submission.
- Credential stuffing — partially addressed: login-attempt rate limiting exists in-process only
  this phase (see [06-rate-limiting.md](06-rate-limiting.md)); the full L1+L2 design is deferred.
- LLM cost abuse (e.g. one script uploading 500 files is a five-figure bill) — partially
  addressed: `usage_counters`-based monthly quotas exist (see
  [12-billing-and-quotas.md](12-billing-and-quotas.md)); per-request rate limiting on uploads/AI
  job creation is deferred.
- Malicious file uploads (zip bombs, PDF parser exploits) — **not yet fully addressed**. Magic-byte
  sniffing and a size cap exist (see [05-library-and-storage.md](05-library-and-storage.md)); the
  spec's specific guidance — cap decompressed size, cap page count at 500, run parsing with a hard
  timeout — is not yet implemented and should be treated as a gap to close before this ingestion
  pipeline ever accepts untrusted uploads at scale.
- SSRF — the item was cut off before its content arrived. Not addressed; not currently believed to
  be a live risk this phase (no outbound fetch-by-URL feature exists yet), but this should be
  re-checked once any feature accepts a URL as input.

## DPDP compliance — what's covered elsewhere

The age-gate mechanics (birth_year, guardian consent block) are in
[04-identity-and-security.md](04-identity-and-security.md). The spec also references `/me/delete`
(erasure request) and `/me/export` (portability) endpoints in its endpoint map — these are
**deferred**, not built this phase; no user data deletion/export flow exists yet.
