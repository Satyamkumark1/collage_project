-- No migration actually creates the pgvector extension that V7's vector(1024) column needs —
-- it was installed manually on this machine while building pgvector from source (see
-- docs/DECISIONS.md). IF NOT EXISTS keeps this a no-op there while making a fresh database
-- (a new teammate, CI, prod) able to run V7 without a manual step first.
CREATE EXTENSION IF NOT EXISTS vector;
