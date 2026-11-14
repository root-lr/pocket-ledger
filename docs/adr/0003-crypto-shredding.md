# ADR-003 — Chiffrement par utilisateur et crypto-shredding

- Statut : accepté
- Date : 2026-11-05

## Contexte

L'event store est immuable, mais le RGPD impose l'effacement des données personnelles sur demande.

## Décision

Envelope encryption : une DEK AES-256 par utilisateur, chiffrée par une KEK maître. Les champs sensibles des
événements et des read models sont chiffrés avec la DEK (`enc:v1:<keyId>:<base64>`), avec l'identifiant utilisateur en
données authentifiées (AAD). Supprimer un compte détruit la DEK, publie `UserKeyShredded`, et chaque projection purge
ses lignes. Une table `shredded_user` empêche un événement arrivé en retard de recréer des lignes.

## Conséquences

- Les événements restent (intégrité de la chaîne) mais leurs champs sensibles sont illisibles.
- Montants, dates, catégories et devises restent en clair : nécessaires aux agrégats et aux analytics.
- Recherche plein texte uniquement approximative (index aveugles HMAC de mots normalisés).
- La KEK devient critique : sans elle, toutes les données sont perdues. Elle est sauvegardée hors de la VM.
