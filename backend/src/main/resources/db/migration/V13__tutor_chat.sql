-- Tutor chat: one conversation per document (see docs/DECISIONS.md).
CREATE TABLE conversations (
    id          UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents (id),
    owner_id    UUID NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX conversations_document_owner_idx ON conversations (document_id, owner_id, created_at DESC);
CREATE INDEX conversations_owner_idx ON conversations (owner_id, created_at DESC);

CREATE TABLE messages (
    id              UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations (id),
    owner_id        UUID NOT NULL REFERENCES users (id),
    role            VARCHAR(10) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content         TEXT NOT NULL,
    citations       JSONB,
    grounded        BOOLEAN,
    beyond_notes    BOOLEAN,
    model           VARCHAR(100),
    prompt_version  INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX messages_conversation_idx ON messages (conversation_id, created_at ASC);
CREATE INDEX messages_owner_idx ON messages (owner_id, created_at DESC);
