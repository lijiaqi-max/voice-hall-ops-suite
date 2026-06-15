#!/bin/sh
set -eu

: "${POSTGRES_DB:?missing POSTGRES_DB}"
: "${POSTGRES_USER:?missing POSTGRES_USER}"
: "${BACKUP_PASSPHRASE:?missing BACKUP_PASSPHRASE}"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="${1:-./backups}"
mkdir -p "$target"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

docker compose --env-file .env -f docker-compose.yml exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$tmp/database.dump"
docker run --rm -v voice-hall-object-data:/source:ro -v "$tmp:/backup" \
  alpine sh -c 'cd /source && tar czf /backup/objects.tar.gz .'
tar czf - -C "$tmp" database.dump objects.tar.gz |
  openssl enc -aes-256-cbc -salt -pbkdf2 -pass env:BACKUP_PASSPHRASE \
  -out "$target/voice-hall-$stamp.tar.gz.enc"
find "$target" -type f -name '*.enc' -mtime +30 -delete
echo "$target/voice-hall-$stamp.tar.gz.enc"
