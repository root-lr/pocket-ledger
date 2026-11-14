# ADR-006 — Spring JDBC pour l'event store, jOOQ pour l'analytics

- Statut : accepté
- Date : 2026-11-05

## Décision

- **Event store et read models simples** : `JdbcClient`. Six requêtes critiques, lisibles telles quelles pendant une revue
  de concurrence.
- **Analytics** : jOOQ (agrégations, `date_trunc`, `CASE`), avec un DSL typé à la main (`field(name(...), SQLDataType...)`).
- Pas de génération de code jOOQ en V1 : elle exigerait une base au moment du build. À ajouter avec le plugin
  Testcontainers + Flyway si le nombre de requêtes analytics grandit.
- Pas de JPA : le modèle entité-mise à jour est contraire à l'append-only.
