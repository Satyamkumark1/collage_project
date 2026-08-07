CREATE TABLE document_chunks (
    id             UUID PRIMARY KEY,
    document_id    UUID NOT NULL REFERENCES documents (id),
    owner_id       UUID NOT NULL REFERENCES users (id),
    chunk_index    INTEGER NOT NULL,
    content        TEXT NOT NULL,
    token_count    INTEGER NOT NULL,
    page_from      INTEGER,
    page_to        INTEGER,
    section_path   TEXT,
    content_sha256 VARCHAR(64) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX document_chunks_document_idx ON document_chunks (document_id, chunk_index);
