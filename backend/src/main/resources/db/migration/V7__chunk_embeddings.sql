-- Dimension confirmed via a real Voyage AI API call (model voyage-4-lite -> 1024), not guessed.
-- See docs/DECISIONS.md.
CREATE TABLE chunk_embeddings (
    chunk_id      UUID PRIMARY KEY REFERENCES document_chunks (id),
    document_id   UUID NOT NULL REFERENCES documents (id),
    owner_id      UUID NOT NULL REFERENCES users (id),
    embedding     vector(1024) NOT NULL,
    model         VARCHAR(50) NOT NULL,
    model_version VARCHAR(20),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX chunk_embeddings_hnsw_idx ON chunk_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
