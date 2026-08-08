# RAG (Retrieval-Augmented Generation)

## Ingestion pipeline

`Fetch → Sniff → Parse → Normalise → Segment → Chunk → Embed → Index → READY`. Each stage updates
`documents.status` and, this phase, is visible via job progress polling (see
[07-jobs-and-async.md](07-jobs-and-async.md)). Failure at any stage sets a specific
`failure_code` (see [03-api-and-errors.md](03-api-and-errors.md)) with a message a student can
act on.

## Parsing (this phase: PDF, TXT, MD only)

| Format | Library | Must extract | Must reject |
|---|---|---|---|
| PDF | Apache PDFBox | Text with page numbers | Encrypted, or < 100 chars/page averaged over the doc (scanned-image detection) |
| TXT/MD | Direct read | Markdown headings as hierarchy | Non-UTF8 that fails charset detection |

DOCX (Apache POI XWPF) and PPTX (Apache POI XSLF) parsing are **deferred** — see
[00-product-and-constraints.md](00-product-and-constraints.md) constraint table, deviation #8 in
the build plan.

**Normalisation:** collapse repeated whitespace, dehyphenate line-break splits, strip repeated
headers/footers detected across ≥60% of pages, drop page-number-only lines, preserve
formulas/code blocks verbatim.

## Chunking

- Structure-aware first: split on detected headings so a chunk never straddles two topics.
- Then token-based within a section: target 700 tokens, hard max 1000, overlap 120 tokens.
  (This phase: token counting is an approximation, not a real tokenizer — documented
  simplification, revisit if chunk-size quality becomes a problem.)
- Never split mid-sentence, mid-table, mid-formula, mid-code-block.
- Discard chunks under 80 tokens unless they're a heading + definition pair.
- Store `page_from`/`page_to` and `section_path` on every chunk — these are what make citations
  possible, and citations are the product.

## Embeddings

Groq does not serve embeddings (constraint #1). **Provider: Voyage AI, model `voyage-4-lite`**
(decision recorded in `/docs/DECISIONS.md`) — $0.02/M tokens, 200M free tokens/account as of Aug
2026, current generation, good cost/quality fit for a bursty, budget-sensitive student product.

`EmbeddingClient.embed(List<String>) -> List<float[]>`; `VoyageEmbeddingClient` calls
`https://api.voyageai.com/v1/embeddings`, batched 32–64 chunks per call, retried with backoff.
Output dimension is confirmed via one real API call before the `chunk_embeddings.embedding
vector(N)` column is migrated — never guessed. `model` and `model_version` are stored on every
row so a future re-embed (new model) is a migration, not a rewrite.

## Retrieval — Phase 2

Hybrid retrieval, built for tutor chat. The original master spec's §7 (which would have specified
this in full) was never actually received — see `specs/15-PENDING.md` — so the parameters below
were designed for this build and are recorded, with rationale, in `/docs/DECISIONS.md` rather than
pulled from spec text. Summary generation (Phase 1) is unaffected — it stays map-reduce over a
document's own chunks in stored order, not a retrieval search.

Pipeline, scoped to `(document_id, owner_id)`:

1. **Vector arm** — top 20 nearest chunks by pgvector cosine distance (`<=>`), `hnsw.ef_search=40`.
2. **Lexical arm** — top 20 by Postgres full-text (`ts_rank_cd` over a generated `content_tsv`
   column + GIN index, `plainto_tsquery('english', ?)`).
3. **Fusion** — Reciprocal Rank Fusion, `k=60`, top 8 chunks proceed. A dedicated rerank stage
   (the original spec's "RRF + rerank") is **not implemented** — RRF alone was judged sufficient
   without an eval harness (Phase 3) to demonstrate a reranker earns its added latency/cost; see
   `ROADMAP.md`.
4. **Neighbour expansion** — each fused chunk's immediate `chunk_index ± 1` neighbours are pulled
   in too (dedup, cap 16 total) so a citation's local context survives a chunk boundary.
5. **Confidence floor** — best vector-arm cosine similarity `< 0.35` ⇒ the retrieved context is
   too weak; the tutor refuses (as a normal chat message, not an HTTP error) rather than answering
   from thin material, unless "explain beyond my notes" is on for that message.

`RetrievalService` (in `rag.service`) is the published entry point; `tutor` never queries
`chunk_embeddings`/`document_chunks` directly, per the cross-feature access rule in
[01-architecture.md](01-architecture.md).

## Grounding contract for the tutor

Every assistant message carries a `citations` array (chunk id + page range + section path) and a
`grounded` boolean. Citations are mechanical, not self-reported: retrieved chunks get a numbered
manifest in the prompt, the model cites inline (`[1]`, `[2]`, …), and after the stream completes
those markers are regex-extracted and mapped back to the fixed candidate list — an out-of-range
marker is dropped, never "repaired," since this is free-form streamed text, not the JSON-mode
repair loop [08-ai-layer.md](08-ai-layer.md) uses for summaries. The "explain beyond my notes"
toggle (per-message) skips the confidence floor and permits general knowledge, with the system
prompt requiring non-notes content to be flagged so a student can tell grounded from ungrounded at
a glance. Full rationale for every number above: `/docs/DECISIONS.md`.
