-- Lexical search arm for hybrid retrieval (see specs/09-rag.md §Retrieval, docs/DECISIONS.md).
ALTER TABLE document_chunks ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX document_chunks_content_tsv_idx ON document_chunks USING GIN (content_tsv);
