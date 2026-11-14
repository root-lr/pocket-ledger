-- Clés de chiffrement par utilisateur (DEK chiffrée par la KEK maître) et identité.

CREATE TABLE user_keys (
    user_id     UUID PRIMARY KEY,
    key_id      UUID        NOT NULL,
    wrapped_dek BYTEA       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email_enc       TEXT,                 -- NULL après suppression
    email_index     TEXT        NOT NULL UNIQUE, -- index aveugle HMAC de l'email normalisé
    password_hash   TEXT        NOT NULL, -- Argon2id
    email_verified  BOOLEAN     NOT NULL DEFAULT false,
    failed_attempts INT         NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    deleted_at      TIMESTAMPTZ
);

CREATE TABLE devices (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id),
    name         TEXT        NOT NULL,
    platform     TEXT        NOT NULL,
    push_token   TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX devices_user_idx ON devices (user_id);

CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id),
    device_id  UUID        NOT NULL REFERENCES devices (id),
    family_id  UUID        NOT NULL,
    token_hash TEXT        NOT NULL UNIQUE, -- SHA-256, jamais le jeton en clair
    roles      TEXT        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

CREATE TABLE user_preferences (
    user_id                UUID PRIMARY KEY REFERENCES users (id),
    reference_currency     CHAR(3) NOT NULL,
    timezone               TEXT    NOT NULL,
    budget_month_start_day INT     NOT NULL DEFAULT 1 CHECK (budget_month_start_day BETWEEN 1 AND 28),
    language               TEXT    NOT NULL DEFAULT 'fr',
    disabled_alerts        TEXT[]  NOT NULL DEFAULT '{}'
);

-- Utilisateurs dont la clé a été détruite : les processeurs ignorent leurs événements arrivés en retard.
CREATE TABLE shredded_user (
    user_id     UUID PRIMARY KEY,
    shredded_at TIMESTAMPTZ NOT NULL
);

-- Actions sensibles hors métier : append-only.
CREATE TABLE security_audit (
    id         UUID PRIMARY KEY,
    user_id    UUID,
    action     TEXT        NOT NULL,
    details    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX security_audit_user_idx ON security_audit (user_id, created_at);
CREATE TRIGGER security_audit_append_only BEFORE UPDATE OR DELETE ON security_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
