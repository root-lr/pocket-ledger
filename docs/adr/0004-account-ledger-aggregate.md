# ADR-004 — Un seul agrégat pour le compte et son grand livre

- Statut : accepté
- Date : 2026-11-05

## Contexte

Le PRD citait deux agrégats, `Account` et `Ledger`. Or les invariants d'une transaction (compte actif, devise du compte,
répartition équilibrée, révision attendue) dépendent de l'état du compte.

## Décision

Un agrégat `Account` porte le solde et l'état de ses transactions. Les virements entre comptes passent par une saga
(`Transfer`) avec des identifiants de transaction déterministes, ce qui rend chaque étape rejouable.

## Conséquences

- Invariants vérifiés atomiquement, sans coordination.
- Un compte très actif accumule des événements : snapshots tous les 100 événements (ADR-005).
- Deux écritures concurrentes sur le même compte se sérialisent par la version de l'agrégat (rejouées jusqu'à 3 fois).
