# Identity & Security

## Token model

Access token: short-lived (15 min) JWT held in memory by the SPA. Refresh token: in an
`HttpOnly; Secure; SameSite=None; Path=/api/v1/auth` cookie. Because the refresh endpoint is
cookie-authenticated and cross-site, it — and only it — would need CSRF protection via a
double-submit token in production. Everything else is Bearer-authenticated and needs no CSRF.

**Local-dev deviation** (see `/docs/DECISIONS.md`): over plain `http://localhost`, `Secure` and
`SameSite=None` don't work — the dev profile uses `SameSite=Lax` and no `Secure` flag. The prod
profile keeps the spec's values.

- JWT: HS512 with a ≥64-byte secret (`JWT_SECRET` env var, fail-fast at boot if absent or too
  short). Claims: `sub`, `sid`, `role`, `emailVerified`, `iat`, `exp`, `jti`, `iss`, `aud`.
  Validate `iss`/`aud` on every request. (`plan` claim deferred until billing exists.)
- Refresh rotation with reuse detection: presenting a revoked token revokes the whole
  `family_id` — see `refresh_tokens` in [02-data-model.md](02-data-model.md).
- BCrypt cost 12. Reject passwords under 10 characters. (Breach-list check against a bundled
  top-10k list is a later hardening pass, not this phase.)
- CORS: exact origin allowlist from `CORS_ALLOWED_ORIGIN` env var. Never `*` with credentials.
  Never reflect `Origin`.
- Uniform response time/message for "user exists" vs "user does not exist" on login and
  registration — no user enumeration.
- Multipart, header, and JSON body size caps. Jackson: `FAIL_ON_UNKNOWN_PROPERTIES` on for
  request DTOs; explicit allowlisted binding — never bind a JPA entity to a request body.
- Timing-safe comparison for all tokens.

Deferred this phase: security headers (HSTS, CSP, etc. — matter once served over real HTTPS),
full password breach-list check, email verification (see below).

## Email verification — deviation

No SMTP provider is configured this phase. `email_verified_at` is set at registration time
instead of through a real verify flow. This is a documented deviation (`/docs/DECISIONS.md`) —
the column and any future gate logic already exist, so wiring real email later is additive, not a
rewrite.

## DPDP age gate

Under the Indian DPDP Act 2023, users under 18 require verifiable parental consent, and
behavioural tracking/targeted ads at them are prohibited. Many BCA first-years are 17, so this
can't be skipped.

**This phase:** `birth_year` (smallint) is captured at registration. Any AI-feature endpoint
computes age from `birth_year`; if under 18 and `guardian_consent_at IS NULL`, the request is
rejected with `403 AUTH_GUARDIAN_CONSENT_REQUIRED` (see
[03-api-and-errors.md](03-api-and-errors.md)). This is a real, enforced block, not a stub.

**Deferred:** the actual consent-collection flow (email to a guardian, a consent UI, recording
the guardian's identity). That's expensive UX with no user-facing feature depending on it yet —
the gate itself is what's correctness-critical to have from day one; the collection flow is a
follow-up phase. See `ROADMAP.md`.

## Threat model addressed this phase

- IDOR on document/summary/job endpoints → the tenancy-enforcement rule in
  [02-data-model.md](02-data-model.md) (ArchUnit-checked `findByIdAndOwnerId`).
- Prompt injection via uploaded documents → see [08-ai-layer.md](08-ai-layer.md) §Prompt-injection
  defence.
- Malicious uploads (oversized files, wrong content behind a spoofed extension) → magic-byte
  sniffing on upload, size caps — see [05-library-and-storage.md](05-library-and-storage.md).

Threat-model items not yet addressed (credential stuffing/rate limiting, LLM cost abuse beyond
basic quotas, zip-bomb/decompression limits, full SSRF review) are listed in
[14-security-privacy-compliance.md](14-security-privacy-compliance.md) and
[15-PENDING.md](15-PENDING.md) — the original spec's §12 was truncated before its full threat
list could be captured here.
