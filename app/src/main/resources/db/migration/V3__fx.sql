-- Référentiel ISO 4217 et taux de change quotidiens.

CREATE TABLE currency (
    code         CHAR(3) PRIMARY KEY,
    minor_digits SMALLINT NOT NULL CHECK (minor_digits BETWEEN 0 AND 4),
    name         TEXT     NOT NULL,
    active       BOOLEAN  NOT NULL DEFAULT true
);

CREATE TABLE fx_rate (
    rate_date DATE            NOT NULL,
    base      CHAR(3)         NOT NULL REFERENCES currency,
    quote     CHAR(3)         NOT NULL REFERENCES currency,
    rate      NUMERIC(24, 12) NOT NULL CHECK (rate > 0), -- unités de quote pour 1 base
    source    TEXT            NOT NULL,                  -- fournisseur, 'manual' ou 'fixed-peg'
    PRIMARY KEY (rate_date, base, quote)
);
CREATE INDEX fx_rate_lookup_idx ON fx_rate (base, quote, rate_date DESC);
