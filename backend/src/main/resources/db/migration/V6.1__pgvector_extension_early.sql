-- V7__chunk_embeddings.sql (next) uses the vector column type, but V10__pgvector_extension.sql
-- doesn't create the extension until 3 migrations later. That gap only ever worked because every
-- database this project touched had pgvector manually pre-installed out-of-band before Flyway
-- first ran on it -- invisible until a genuinely fresh database (Testcontainers, or a recreated
-- local dev DB) hit it for real. V7/V10 are already-applied, checksummed migrations elsewhere and
-- can't be renumbered (see docs/DECISIONS.md) -- Flyway supports inserting a migration between
-- two existing version numbers (V6 and V7) via this dotted version, which is what a fresh
-- database needs and V10's own `CREATE EXTENSION IF NOT EXISTS` still runs harmlessly.
CREATE EXTENSION IF NOT EXISTS vector;
