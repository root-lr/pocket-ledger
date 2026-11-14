#!/bin/sh
# Sauvegarde quotidienne chiffrée de PostgreSQL (PRD §10.4). À synchroniser vers un stockage objet externe
# (rclone, aws s3 cp…) : une sauvegarde qui reste sur la VM ne protège pas contre la perte de la VM.
set -eu

export PGPASSWORD="$(cat "${PGPASSWORD_FILE}")"
PASSPHRASE_FILE="${BACKUP_PASSPHRASE_FILE}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET="/backups/pocketledger-${STAMP}.dump.gpg"

apk add --no-cache gnupg >/dev/null 2>&1 || true
pg_dump -h postgres -U pocketledger -d pocketledger -Fc \
  | gpg --batch --yes --pinentry-mode loopback --passphrase-file "${PASSPHRASE_FILE}" --symmetric --cipher-algo AES256 -o "${TARGET}"

# Rétention : 14 jours
find /backups -name 'pocketledger-*.dump.gpg' -mtime +14 -delete
echo "Sauvegarde écrite : ${TARGET}"
