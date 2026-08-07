# Pending — Not Yet Provided

The pasted "StudyFlow AI — Master Build Spec v2.0" was truncated by a 50,000-character message
limit while pasting §12 (Security, Privacy, Compliance), mid-sentence, at "SSRF via…". The user
said they'd paste the remainder; it has not arrived as of this writing (2026-08-08).

**Missing:** the rest of §12's threat model, and everything from §13 through §17, which per the
spec's own table of contents structure would cover (numbering inferred from context, not
confirmed):

- §12 remainder — rest of the threat model (SSRF details onward), likely also covering secrets
  management, dependency/supply-chain hygiene, and India-specific compliance detail beyond DPDP
  age-gating.
- §13 — unknown scope, not covered by any other section.
- §14 — referenced elsewhere in the spec as covering **JVM tuning** ("Tune JVM per §14.2" appears
  in the hard-constraints table) — likely a deployment/ops sizing section.
- §15 — referenced elsewhere as the **budget** section ("Budget in §15" appears in the
  hard-constraints table) — likely infra cost planning.
- §16 — unknown scope.
- §17 — referenced explicitly as **Non-Goals** ("Anything in §17 (Non-Goals) is out" — §0.4 Scope
  discipline). This is the one most worth getting: without it, scope boundaries for later phases
  (MCQs, tutor, billing, etc.) rely on inference from what's described elsewhere rather than an
  explicit exclusion list.

**Handling:** nothing in this `specs/` folder invents content for these sections. When the
remainder is provided, it should be decomposed into the existing module files above (most of it
maps onto [14-security-privacy-compliance.md](14-security-privacy-compliance.md),
[13-observability-and-ops.md](13-observability-and-ops.md), and a new
`16-non-goals.md`/deployment-budget file), and this file should be deleted once nothing is left
pending.
