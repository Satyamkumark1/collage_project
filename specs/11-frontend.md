# Frontend

## Stack

React 19, Vite, TypeScript strict. TanStack Query for server state; Zustand (if/when needed) only
for ephemeral UI state. React Router. Feature-first folders. This phase: a small hand-written API
client for the handful of endpoints in play (see
[03-api-and-errors.md](03-api-and-errors.md)) — a generated OpenAPI client is worth adding once
the endpoint surface is large enough to make hand-maintenance error-prone.

## Design direction — "Answer Booklet"

Visual world: the Indian examination answer script — cool off-white ruled paper, blue ballpoint
ink, the examiner's red pen, a highlighter. Every colour has a job.

**Tokens**

| Role | Token | Value | Used for |
|---|---|---|---|
| Surface | `--paper` | `#F7F7F4` | App background (light) |
| Surface raised | `--sheet` | `#FFFFFF` | Cards, sheets |
| Rule | `--rule` | `#DCDCD4` | Hairlines, ruled-line motif |
| Ink | `--ink` | `#16213E` | Primary text, primary buttons |
| Ink muted | `--ink-60` | `#16213E` at 60% | Secondary text |
| Correction | `--red-pen` | `#C8362C` | Wrong answers, destructive only |
| Marked | `--highlight` | `#F5D547` | Highlighted source, due-now |
| Verified | `--check` | `#1F7A5C` | Correct answers, grounded citations |

Dark mode is the desk lamp at 2 a.m., not an inverted app: `--paper` → `#14161A`, ink → warm bone
`#E8E4DA`, highlight yellow desaturated slightly. Warm-shifted, not blue-black.

**Type.** Display serif (Fraunces or similar) for page titles/question stems only. Body: neutral
sans (Public Sans/Inter Tight), 16px base, 1.6 line-height, `65ch` max measure. Numerals: mono
face, tabular figures, for timers/scores/counters.

**Signature element — the Source Rail.** A persistent citation panel on every AI-output surface.
This phase: a simple citation list under the summary (chunk reference + snippet), not yet the
full hover-linked, page-referenced rail from the spec — that richness (linking each sentence to
its chunk, highlighter-sweep hover interaction, "unsourced" dotted-underline marker) is built out
once there's more than one AI-generated surface to justify the interaction design investment.
Even the simple version must make the trust argument visible: every citation shown is a real
chunk reference the backend validated, not decorative.

**Secondary motif — OMR bubbles.** Deferred — no single-choice controls exist yet (MCQs/quizzes
aren't built this phase).

**Restraint.** No glassmorphism, no gradient meshes, no animated blobs. Motion limited to page
transitions and simple state changes; respect `prefers-reduced-motion`.

## Async UX

Generation takes 20–180s — a spinner for two minutes is a bug report.

- Optimistic insert of a placeholder card into the document/summary list the moment a job is
  enqueued.
- Poll `GET /jobs/{id}` at 1500ms, backing off to 5s after 30s. Stop on terminal state.
- Job lives on the server — leaving the page doesn't cancel it; on return, in-flight jobs are
  re-attached from `GET /jobs`.
- Failure state is actionable: what failed, why, retry button.

SSE streaming for job progress is deferred (needs Redis — see
[07-jobs-and-async.md](07-jobs-and-async.md)); polling is the only path this phase, which is also
the spec's documented fallback, not a lesser mode.

## Every screen needs five states

Loading (skeletons shaped like real content, never a bare spinner), empty (instruction + primary
action), error (what happened, how to fix, retry), partial (not applicable yet — no batch
generation this phase), success. Applies to Library and Document Detail this phase.

## Quality floor

Keyboard-navigable, visible focus rings (never `outline: none`), ≥44px touch targets, AA
contrast, screen-reader live regions where content streams/updates. Works at 360px width.

## Pages — this phase

Register · Login · Library (list, empty state, upload action) · Upload · Document Detail (reader
status + "Generate Summary" + job progress + summary with citation list) · Tutor (Phase 2 — chat
thread per document, streamed replies, "explain beyond my notes" toggle, per-message grounding
badge and citation rail — see [09-rag.md](09-rag.md) §Grounding contract).

Streamed tutor replies are read via `fetch` + `ReadableStream`, not `EventSource` — the endpoint
is a `POST` with an `Authorization` header, neither of which `EventSource` supports. Tokens arrive
as they stream; the assistant bubble shows accumulated text with a typing-cursor indicator
(`prefers-reduced-motion`-respecting) until the `done` event lands with citations and the
grounded/beyond-notes flag.

Deferred pages (Landing, Pricing, Dashboard, Summaries-as-own-page, Key points, Flashcards,
Quizzes, Planner, Usage & billing, Settings, Admin) get added as their features land.
