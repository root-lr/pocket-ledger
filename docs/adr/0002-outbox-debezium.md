# ADR-002 — Outbox publiée par Debezium plutôt qu'un relay maison

- Statut : accepté (remplace l'outbox pollée envisagée initialement)
- Date : 2026-11-05

## Contexte

Écrire dans PostgreSQL puis publier sur Kafka sans transaction distribuée exige le pattern outbox.
Deux options : un relay applicatif qui lit l'outbox (`FOR UPDATE SKIP LOCKED`), ou la capture du WAL par Debezium.

## Décision

Debezium (connecteur PostgreSQL, `pgoutput`) avec l'Outbox Event Router. L'application insère dans `outbox` dans la
même transaction que les événements, puis supprime la ligne : la table reste vide, le WAL garde l'insertion.
Une publication dédiée (`pocketledger_outbox`) limite la capture à cette table. Le connecteur est enregistré au
démarrage par l'application (`PUT /connectors/{name}/config`, idempotent).

## Conséquences

- Plus de code de relay, latence plus faible, ordre garanti par `aggregate_id`.
- Nouveau point de vigilance : un slot de réplication non consommé retient le WAL. Mitigations :
  `max_slot_wal_keep_size`, métrique `pocketledger.debezium.slot.lag.bytes`, alerte au-delà de 1 Go.
- Livraison au moins une fois : tous les consommateurs sont idempotents (checkpoint par agrégat et version).
- Une brique d'infrastructure de plus (Kafka Connect), testée de bout en bout avec Testcontainers.
