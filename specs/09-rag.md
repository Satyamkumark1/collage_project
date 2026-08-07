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

## Retrieval — deferred

Hybrid retrieval (vector search + Postgres full-text, Reciprocal Rank Fusion, rerank, neighbour
expansion, context assembly) is specified in full in the original spec but **not built this
phase** — summary generation (the only AI feature this phase) is map-reduce over a document's own
chunks in their stored order, not a retrieval search. Chunks and embeddings are still generated
and stored during ingestion precisely so the next phase (tutor chat, which needs retrieval) isn't
blocked on a re-ingestion migration.

## Grounding contract for the tutor — deferred

The confidence-floor refusal behaviour, the mandatory `citations` array on every assistant
message, and the "Explain beyond my notes" escape hatch are all tutor-chat concerns — see
`ROADMAP.md` for when that phase starts. The summary feature built this phase already carries its
own citations (chunk references validated against the document, per
[08-ai-layer.md](08-ai-layer.md) §Structured output), so the grounding principle is applied now
even though the full tutor contract isn't built yet.
