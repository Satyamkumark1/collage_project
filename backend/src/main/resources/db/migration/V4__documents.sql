CREATE TABLE documents (
    id                UUID PRIMARY KEY,
    owner_id          UUID NOT NULL REFERENCES users (id),
    title             TEXT NOT NULL,
    original_filename TEXT NOT NULL,
    mime_type         TEXT NOT NULL,
    file_type         VARCHAR(10) NOT NULL CHECK (file_type IN ('PDF', 'TXT', 'MD')),
    size_bytes        BIGINT NOT NULL,
    content_sha256    VARCHAR(64) NOT NULL,
    storage_key       TEXT NOT NULL,
    storage_provider  VARCHAR(20) NOT NULL,
    page_count        INTEGER,
    char_count        INTEGER,
    language          VARCHAR(10),
    status            VARCHAR(20) NOT NULL DEFAULT 'UPLOADED'
                          CHECK (status IN ('UPLOADED', 'PARSING', 'CHUNKING', 'EMBEDDING', 'READY', 'FAILED')),
    failure_code      VARCHAR(50),
    failure_detail    TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ
);

CREATE UNIQUE INDEX documents_owner_sha256_unique_idx ON documents (owner_id, content_sha256)
    WHERE deleted_at IS NULL;
CREATE INDEX documents_owner_created_idx ON documents (owner_id, created_at DESC) WHERE deleted_at IS NULL;
