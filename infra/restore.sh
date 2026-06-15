#!/bin/sh
set -eu

backup="${1:?usage: restore.sh BACKUP_FILE}"
: "${POSTGRES_DB:?missing POSTGRES_DB}"
: "${POSTGRES_USER:?missing POSTGRES_USER}"
: "${BACKUP_PASSPHRASE:?missing BACKUP_PASSPHRASE}"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_PASSPHRASE \
  -in "$backup" | tar xzf - -C "$tmp"

docker compose --env-file .env -f docker-compose.yml exec -T postgres \
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists < "$tmp/database.dump"
docker run --rm -v voice-hall-object-data:/target -v "$tmp:/backup:ro" \
  alpine sh -c 'rm -rf /target/* && tar xzf /backup/objects.tar.gz -C /target'
echo "restore completed"
