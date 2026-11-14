-- Catégories (2 niveaux, identifiants hiérarchiques), règles, détection des abonnements.

CREATE TABLE category (
    id         TEXT PRIMARY KEY,
    user_id    UUID,         -- NULL = catégorie par défaut
    parent_id  TEXT REFERENCES category (id),
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX category_user_idx ON category (user_id);

INSERT INTO category (id, parent_id, name) VALUES
    ('food', NULL, 'Alimentation'),
    ('housing', NULL, 'Logement'),
    ('transport', NULL, 'Transport'),
    ('health', NULL, 'Santé'),
    ('leisure', NULL, 'Loisirs'),
    ('shopping', NULL, 'Achats'),
    ('education', NULL, 'Éducation'),
    ('family', NULL, 'Famille'),
    ('income', NULL, 'Revenus'),
    ('finance', NULL, 'Finances'),
    ('transfers', NULL, 'Virements'),
    ('other', NULL, 'Divers');

INSERT INTO category (id, parent_id, name) VALUES
    ('food.groceries', 'food', 'Courses'),
    ('food.restaurants', 'food', 'Restaurants'),
    ('food.coffee', 'food', 'Cafés'),
    ('housing.rent', 'housing', 'Loyer'),
    ('housing.utilities', 'housing', 'Énergie et eau'),
    ('housing.maintenance', 'housing', 'Entretien'),
    ('transport.fuel', 'transport', 'Carburant'),
    ('transport.public', 'transport', 'Transports en commun'),
    ('transport.taxi', 'transport', 'Taxi et VTC'),
    ('health.pharmacy', 'health', 'Pharmacie'),
    ('health.doctor', 'health', 'Médecin'),
    ('leisure.subscriptions', 'leisure', 'Abonnements'),
    ('leisure.outings', 'leisure', 'Sorties'),
    ('leisure.travel', 'leisure', 'Voyages'),
    ('shopping.clothing', 'shopping', 'Vêtements'),
    ('shopping.electronics', 'shopping', 'Électronique'),
    ('education.school', 'education', 'Scolarité'),
    ('education.books', 'education', 'Livres'),
    ('family.support', 'family', 'Soutien familial'),
    ('family.gifts', 'family', 'Cadeaux'),
    ('income.salary', 'income', 'Salaire'),
    ('income.freelance', 'income', 'Missions'),
    ('income.other', 'income', 'Autres revenus'),
    ('finance.fees', 'finance', 'Frais bancaires'),
    ('finance.savings', 'finance', 'Épargne'),
    ('finance.loan', 'finance', 'Remboursement de prêt'),
    ('transfers.internal', 'transfers', 'Virement interne'),
    ('other.misc', 'other', 'Divers');

CREATE TABLE category_rule (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL,
    keyword_enc TEXT        NOT NULL,
    category_id TEXT        NOT NULL REFERENCES category (id),
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX category_rule_user_idx ON category_rule (user_id);

CREATE TABLE recurring_occurrence (
    transaction_id UUID PRIMARY KEY,
    user_id        UUID    NOT NULL,
    account_id     UUID    NOT NULL,
    merchant_hash  TEXT    NOT NULL,
    amount_minor   BIGINT  NOT NULL,
    currency       CHAR(3) NOT NULL,
    value_date     DATE    NOT NULL,
    voided         BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX recurring_occurrence_lookup_idx ON recurring_occurrence (user_id, merchant_hash, currency, value_date DESC);

CREATE TABLE recurring_series (
    series_id     UUID PRIMARY KEY,
    user_id       UUID        NOT NULL,
    merchant_hash TEXT        NOT NULL,
    currency      CHAR(3)     NOT NULL,
    interval_days INT         NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, merchant_hash, currency)
);
