#!/usr/bin/env bash
# Déploiement sur la VM de démo (appelé par la CI en SSH, ou à la main).
#   DOMAIN=api.example.com ./ops/scripts/deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

: "${DOMAIN:?DOMAIN requis (ex. api.example.com)}"
[ -f secrets/app.env ] || { echo "Lancez d'abord ops/scripts/generate-secrets.sh"; exit 1; }

export DOMAIN
export DB_PASSWORD="$(cat secrets/db_password)"
export APP_IMAGE="${APP_IMAGE:-ghcr.io/pocketledger/pocketledger:latest}"

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile full)
"${COMPOSE[@]}" pull app
"${COMPOSE[@]}" up -d --remove-orphans
"${COMPOSE[@]}" ps
