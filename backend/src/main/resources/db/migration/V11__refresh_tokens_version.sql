-- Optimistic locking column for RefreshTokenService.rotate's concurrent-reuse fencing.
ALTER TABLE refresh_tokens ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
