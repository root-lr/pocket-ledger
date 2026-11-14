-- Event store append-only, snapshots, outbox (lue par Debezium), checkpoints des processeurs, idempotence.

CREATE TABLE event_store (
    global_position BIGSERIAL PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE,
    aggregate_type  TEXT        NOT NULL,
    aggregate_id    UUID        NOT NULL,
    version         INT         NOT NULL,
    event_type      TEXT        NOT NULL,
    schema_version  INT         NOT NULL DEFAULT 1,
    user_id         UUID        NOT NULL,
    payload         JSONB       NOT NULL, -- champs sensibles chiffrés, montants dans leur devise d'origine
    metadata        JSONB       NOT NULL, -- correlationId, causationId, idempotencyKey, deviceId
    occurred_at     TIMESTAMPTZ NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (aggregate_id, version)
);

CREATE INDEX event_store_user_idx ON event_store (user_id, global_position);

CREATE FUNCTION forbid_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % en append-only : % interdit', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER event_store_append_only BEFORE UPDATE OR DELETE ON event_store
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER event_store_no_truncate BEFORE TRUNCATE ON event_store
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();

CREATE TABLE snapshots (
    aggregate_id     UUID PRIMARY KEY,
    user_id          UUID        NOT NULL,
    version          INT         NOT NULL,
    snapshot_version INT         NOT NULL, -- format inconnu = snapshot ignoré
    state            BYTEA       NOT NULL, -- JSON chiffré avec la clé de l'utilisateur
    taken_at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX snapshots_user_idx ON snapshots (user_id);

-- Lue par Debezium dans le WAL ; chaque ligne est supprimée dans la transaction qui l'a écrite.
CREATE TABLE outbox (
    id             UUID PRIMARY KEY,           -- = event_id
    aggregate_type TEXT        NOT NULL,       -- contexte : route vers le topic pocketledger.<contexte>.events.v1
    aggregate_id   TEXT        NOT NULL,       -- clé Kafka : ordre garanti par agrégat
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,       -- enveloppe complète de l'événement
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Publication logique dédiée : le connecteur Debezium ne voit que l'outbox.
CREATE PUBLICATION pocketledger_outbox FOR TABLE outbox;

CREATE TABLE processor_checkpoint (
    processor     TEXT        NOT NULL,
    aggregate_id  UUID        NOT NULL,
    last_version  INT         NOT NULL,
    last_position BIGINT      NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (processor, aggregate_id)
);

CREATE TABLE idempotency_record (
    user_id      UUID        NOT NULL,
    idem_key     TEXT        NOT NULL,
    request_hash TEXT        NOT NULL,
    status       INT         NOT NULL,
    content_type TEXT,
    body         TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, idem_key)
);
CREATE INDEX idempotency_created_idx ON idempotency_record (created_at);
