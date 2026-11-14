# ADR-001 — Event sourcing et CQRS

- Statut : accepté
- Date : 2026-11-05

## Contexte

Une app de finances doit répondre à des questions historiques (« quel était mon solde le 12 ? »), garder une trace
auditable de chaque correction et supporter des lectures très différentes des écritures (analytics, recherche, sync mobile).

## Décision

Les agrégats `Account`, `Transfer`, `Budget`, `ImportJob`, `RecurringSeries` sont event-sourcés dans une table
PostgreSQL append-only. Les lectures passent par des read models dédiés, alimentés par des processeurs Kafka.
Les données de référence (identité, catégories, devises) restent en CRUD.

## Conséquences

- Historique complet et solde à n'importe quelle date ; corrections = nouveaux événements (`TransactionAmended`).
- Cohérence éventuelle côté lecture : mitigée par `X-Aggregate-Version` et `?minVersion=` (attente bornée à 2 s).
- Évolution des schémas d'événements gérée par des upcasters et des contrats JSON Schema versionnés.
