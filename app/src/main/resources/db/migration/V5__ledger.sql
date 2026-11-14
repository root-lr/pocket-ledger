-- Ledger : index d'écriture, read models, imports.

-- Index côté écriture (même transaction que les événements)
CREATE TABLE ledger_account_owner (
    account_id UUID PRIMARY KEY,
    user_id    UUID NOT NULL
);
CREATE INDEX ledger_account_owner_user_idx ON ledger_account_owner (user_id);

CREATE TABLE ledger_transaction_index (
    transaction_id UUID PRIMARY KEY,
    account_id     UUID NOT NULL,
    user_id        UUID NOT NULL
);

CREATE TABLE ledger_transfer_owner (
    transfer_id UUID PRIMARY KEY,
    user_id     UUID NOT NULL
);

-- Read models
CREATE TABLE account_view (
    account_id          UUID PRIMARY KEY,
    user_id             UUID        NOT NULL,
    name_enc            TEXT        NOT NULL,
    type                TEXT        NOT NULL,
    currency            CHAR(3)     NOT NULL,
    opening_minor       BIGINT      NOT NULL,
    balance_minor       BIGINT      NOT NULL,
    low_threshold_minor BIGINT,
    archived            BOOLEAN     NOT NULL,
    opened_on           DATE        NOT NULL,
    version             INT         NOT NULL,
    position            BIGINT      NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX account_view_user_idx ON account_view (user_id, position);

CREATE TABLE transaction_view (
    transaction_id UUID PRIMARY KEY,
    account_id     UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    revision       INT         NOT NULL,
    amount_minor   BIGINT      NOT NULL,
    currency       CHAR(3)     NOT NULL,
    value_date     DATE        NOT NULL,
    label_enc      TEXT        NOT NULL,
    merchant_enc   TEXT,
    merchant_hash  TEXT,
    label_tokens   TEXT[]      NOT NULL DEFAULT '{}', -- index aveugles (recherche approximative)
    note_enc       TEXT,
    tags           TEXT[]      NOT NULL DEFAULT '{}',
    parts          JSONB       NOT NULL,
    category_ids   TEXT[]      NOT NULL DEFAULT '{}',
    transfer_id    UUID,
    import_id      UUID,
    voided         BOOLEAN     NOT NULL,
    position       BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX transaction_view_user_date_idx ON transaction_view (user_id, value_date DESC, transaction_id DESC);
CREATE INDEX transaction_view_account_idx ON transaction_view (account_id, value_date);
CREATE INDEX transaction_view_position_idx ON transaction_view (user_id, position);
CREATE INDEX transaction_view_tokens_idx ON transaction_view USING GIN (label_tokens);
CREATE INDEX transaction_view_categories_idx ON transaction_view USING GIN (category_ids);
CREATE INDEX transaction_view_tags_idx ON transaction_view USING GIN (tags);

CREATE TABLE transfer_view (
    transfer_id     UUID PRIMARY KEY,
    user_id         UUID        NOT NULL,
    from_account_id UUID        NOT NULL,
    to_account_id   UUID        NOT NULL,
    debit_minor     BIGINT      NOT NULL,
    debit_currency  CHAR(3)     NOT NULL,
    credit_minor    BIGINT      NOT NULL,
    credit_currency CHAR(3)     NOT NULL,
    fee_minor       BIGINT      NOT NULL,
    implied_rate    TEXT,
    value_date      DATE        NOT NULL,
    status          TEXT        NOT NULL,
    position        BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX transfer_view_status_idx ON transfer_view (status, created_at);

-- Imports
CREATE TABLE import_job (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL,
    account_id   UUID        NOT NULL,
    file_hash    TEXT        NOT NULL,
    format       TEXT        NOT NULL,
    mapping      JSONB,
    content_enc  BYTEA,        -- fichier chiffré, effacé à la fin du traitement
    status       TEXT        NOT NULL,
    imported     INT         NOT NULL DEFAULT 0,
    duplicates   INT         NOT NULL DEFAULT 0,
    failed       INT         NOT NULL DEFAULT 0,
    errors       JSONB,
    created_at   TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);
CREATE INDEX import_job_hash_idx ON import_job (account_id, file_hash);

CREATE TABLE import_fingerprint (
    account_id     UUID NOT NULL,
    fingerprint    TEXT NOT NULL,
    transaction_id UUID NOT NULL,
    PRIMARY KEY (account_id, fingerprint)
);

CREATE TABLE import_duplicate (
    import_id   UUID        NOT NULL,
    row_number  INT         NOT NULL,
    fingerprint TEXT        NOT NULL,
    row_data    BYTEA       NOT NULL, -- ligne chiffrée
    resolved_at TIMESTAMPTZ,
    PRIMARY KEY (import_id, row_number)
);

CREATE TABLE import_mapping (
    user_id    UUID  NOT NULL,
    account_id UUID  NOT NULL,
    mapping    JSONB NOT NULL,
    PRIMARY KEY (user_id, account_id)
);
