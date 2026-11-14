#!/usr/bin/env bash
# Génère les secrets de la VM de démo dans ops/secrets/ (ignoré par git).
#   ./ops/scripts/generate-secrets.sh
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)/secrets"
mkdir -p "$DIR"
chmod 700 "$DIR"

openssl rand -base64 24 | tr -d '\n' > "$DIR/db_password"
openssl rand -base64 32 | tr -d '\n' > "$DIR/backup_passphrase"

# Paire ES256 pour signer les JWT
openssl ecparam -name prime256v1 -genkey -noout -out "$DIR/jwt-ec.pem"
openssl pkcs8 -topk8 -nocrypt -in "$DIR/jwt-ec.pem" -out "$DIR/jwt-private.pem"
openssl ec -in "$DIR/jwt-ec.pem" -pubout -out "$DIR/jwt-public.pem" 2>/dev/null

cat > "$DIR/app.env" <<EOF
DB_PASSWORD=$(cat "$DIR/db_password")
CRYPTO_MASTER_KEY=$(openssl rand -base64 32)
CRYPTO_BLIND_INDEX_KEY=$(openssl rand -base64 32)
JWT_PRIVATE_KEY=$(tr -d '\n' < "$DIR/jwt-private.pem")
JWT_PUBLIC_KEY=$(tr -d '\n' < "$DIR/jwt-public.pem")
EOF
chmod 600 "$DIR"/*

echo "Secrets générés dans $DIR"
echo "IMPORTANT : sauvegardez CRYPTO_MASTER_KEY hors de la VM. Sans elle, toutes les données chiffrées sont perdues."
