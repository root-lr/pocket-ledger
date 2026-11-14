# ADR-005 — Snapshots en JSON chiffré, traités comme un cache jetable

- Statut : accepté
- Date : 2026-11-05

## Décision

Un snapshot tous les 100 événements, sérialisé en JSON (Jackson) puis chiffré avec la DEK de l'utilisateur.
Chaque snapshot porte un `snapshot_version` : si le format de l'état change, les anciens snapshots sont ignorés et
l'agrégat est reconstruit depuis les événements. Aucune migration de snapshot n'est écrite.

## Alternatives écartées

- **Protobuf** : compact et bien versionné, mais oblige à maintenir des fichiers `.proto` en double de l'état.
- **Kryo** : très rapide, mais fragile au renommage de classe et délicat avec les data classes Kotlin.
- **Sans snapshot** : à 500 transactions par mois, un compte dépasse 6 000 événements par an.
