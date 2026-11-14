-- Budgets ------------------------------------------------------------------

CREATE TABLE budget_owner (
    budget_id UUID PRIMARY KEY,
    user_id   UUID NOT NULL
);
CREATE INDEX budget_owner_user_idx ON budget_owner (user_id);

CREATE TABLE budget_view (
    budget_id   UUID PRIMARY KEY,
    user_id     UUID    NOT NULL,
    category_id TEXT    NOT NULL,
    currency    CHAR(3) NOT NULL,
    rollover    BOOLEAN NOT NULL,
    start_month TEXT    NOT NULL, -- YYYY-MM (comparable lexicalement)
    closed_from TEXT
);
CREATE INDEX budget_view_user_idx ON budget_view (user_id);

CREATE TABLE budget_tx (
    transaction_id UUID PRIMARY KEY,
    user_id        UUID    NOT NULL,
    value_date     DATE    NOT NULL,
    currency       CHAR(3) NOT NULL,
    parts          JSONB   NOT NULL,
    is_transfer    BOOLEAN NOT NULL,
    voided         BOOLEAN NOT NULL
);
CREATE INDEX budget_tx_user_idx ON budget_tx (user_id);

CREATE TABLE budget_contribution (
    transaction_id UUID   NOT NULL,
    part_index     INT    NOT NULL,
    budget_id      UUID   NOT NULL,
    month          TEXT   NOT NULL,
    amount_minor   BIGINT NOT NULL, -- dans la devise du budget
    PRIMARY KEY (transaction_id, part_index, budget_id)
);

CREATE TABLE budget_month (
    budget_id   UUID   NOT NULL,
    month       TEXT   NOT NULL,
    spent_minor BIGINT NOT NULL,
    PRIMARY KEY (budget_id, month)
);

-- Analytics ----------------------------------------------------------------

CREATE TABLE analytics_account (
    account_id    UUID PRIMARY KEY,
    user_id       UUID    NOT NULL,
    currency      CHAR(3) NOT NULL,
    opening_minor BIGINT  NOT NULL,
    opened_on     DATE    NOT NULL
);
CREATE INDEX analytics_account_user_idx ON analytics_account (user_id);

CREATE TABLE analytics_tx (
    transaction_id UUID PRIMARY KEY,
    user_id        UUID    NOT NULL,
    account_id     UUID    NOT NULL,
    value_date     DATE    NOT NULL,
    currency       CHAR(3) NOT NULL,
    amount_minor   BIGINT  NOT NULL,
    merchant_hash  TEXT,
    merchant_enc   TEXT,
    parts          JSONB   NOT NULL,
    is_transfer    BOOLEAN NOT NULL,
    voided         BOOLEAN NOT NULL
);
CREATE INDEX analytics_tx_user_idx ON analytics_tx (user_id, account_id, value_date);

CREATE TABLE analytics_tx_part (
    transaction_id   UUID    NOT NULL,
    part_index       INT     NOT NULL,
    user_id          UUID    NOT NULL,
    account_id       UUID    NOT NULL,
    value_date       DATE    NOT NULL,
    category_id      TEXT,
    amount_minor     BIGINT  NOT NULL,
    currency         CHAR(3) NOT NULL,
    amount_ref_minor BIGINT,          -- NULL = non converti (taux indisponible)
    ref_currency     CHAR(3) NOT NULL,
    merchant_hash    TEXT,
    merchant_enc     TEXT,
    is_transfer      BOOLEAN NOT NULL,
    PRIMARY KEY (transaction_id, part_index)
);
CREATE INDEX analytics_tx_part_user_date_idx ON analytics_tx_part (user_id, value_date);

CREATE TABLE analytics_recurring (
    series_id        UUID PRIMARY KEY,
    user_id          UUID        NOT NULL,
    account_id       UUID        NOT NULL,
    merchant_enc     TEXT        NOT NULL,
    amount_minor     BIGINT      NOT NULL,
    currency         CHAR(3)     NOT NULL,
    interval_days    INT         NOT NULL,
    next_expected_on DATE        NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX analytics_recurring_user_idx ON analytics_recurring (user_id);

-- Alertes ------------------------------------------------------------------

CREATE TABLE alert (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL,
    type       TEXT        NOT NULL,
    dedupe_key TEXT        NOT NULL UNIQUE,
    details    JSONB       NOT NULL,
    status     TEXT        NOT NULL, -- NEW, READ, DISMISSED
    position   BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    read_at    TIMESTAMPTZ
);
CREATE INDEX alert_user_idx ON alert (user_id, created_at DESC, id DESC);
CREATE INDEX alert_position_idx ON alert (user_id, position);

CREATE TABLE alert_tx (
    transaction_id UUID PRIMARY KEY,
    user_id        UUID        NOT NULL,
    account_id     UUID        NOT NULL,
    category_id    TEXT,
    amount_minor   BIGINT      NOT NULL,
    currency       CHAR(3)     NOT NULL,
    merchant_hash  TEXT,
    value_date     DATE        NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL,
    voided         BOOLEAN     NOT NULL
);
CREATE INDEX alert_tx_category_idx ON alert_tx (user_id, category_id, currency, value_date);
CREATE INDEX alert_tx_duplicate_idx ON alert_tx (account_id, amount_minor, merchant_hash);

CREATE TABLE alert_balance (
    account_id      UUID PRIMARY KEY,
    user_id         UUID    NOT NULL,
    currency        CHAR(3) NOT NULL,
    balance_minor   BIGINT  NOT NULL,
    threshold_minor BIGINT
);

-- Exports RGPD -------------------------------------------------------------

CREATE TABLE data_export (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL,
    status     TEXT        NOT NULL, -- PENDING, READY, FAILED
    file_path  TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    ready_at   TIMESTAMPTZ
);
