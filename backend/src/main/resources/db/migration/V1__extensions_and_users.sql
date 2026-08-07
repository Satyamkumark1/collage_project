CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id                  UUID PRIMARY KEY,
    email               CITEXT NOT NULL,
    password_hash       TEXT NOT NULL,
    name                TEXT NOT NULL,
    role                VARCHAR(20) NOT NULL DEFAULT 'USER'
                            CHECK (role IN ('USER', 'ADMIN')),
    email_verified_at   TIMESTAMPTZ,
    birth_year          SMALLINT NOT NULL,
    guardian_consent_at TIMESTAMPTZ,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETION_PENDING')),
    locale              VARCHAR(10) NOT NULL DEFAULT 'en-IN',
    timezone            VARCHAR(50) NOT NULL DEFAULT 'Asia/Kolkata',
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ
);

CREATE UNIQUE INDEX users_email_unique_idx ON users (email) WHERE deleted_at IS NULL;
