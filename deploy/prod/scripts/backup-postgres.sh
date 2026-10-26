#!/usr/bin/env bash
# Dump the production database to a compressed custom-format archive, write a
# checksum sidecar, and prune archives older than BACKUP_RETENTION_DAYS.
#
# Usage (from anywhere):
#   deploy/prod/scripts/backup-postgres.sh
#   ENV_FILE=/path/.env COMPOSE_PROJECT_NAME=chatbi-prod .../backup-postgres.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-chatbi-prod}"

[ -f "$ENV_FILE" ] || { echo "missing env file: $ENV_FILE" >&2; exit 1; }
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

RETENTION="$(grep -E '^BACKUP_RETENTION_DAYS=' "$ENV_FILE" | tail -1 | cut -d= -f2 || true)"
RETENTION="${RETENTION:-14}"
case "$RETENTION" in (*[!0-9]*|'') echo "invalid BACKUP_RETENTION_DAYS=$RETENTION" >&2; exit 1;; esac

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/chatbi-$STAMP.dump"

compose() {
    docker compose --project-name "$COMPOSE_PROJECT_NAME" \
        -f "$ROOT/compose.yml" --env-file "$ENV_FILE" "$@"
}

echo "Dumping database to $OUT"
compose exec -T postgres sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$OUT"
chmod 600 "$OUT"
[ -s "$OUT" ] || { echo "dump is empty" >&2; rm -f "$OUT"; exit 1; }

if command -v sha256sum >/dev/null 2>&1; then
    (cd "$BACKUP_DIR" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
else
    (cd "$BACKUP_DIR" && openssl dgst -sha256 "$(basename "$OUT")" > "$(basename "$OUT").sha256")
fi
chmod 600 "$OUT.sha256"

# Prune archives and sidecars older than the retention window.
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'chatbi-*.dump' -o -name 'chatbi-*.dump.sha256' \) \
    -mtime +"$RETENTION" -print -delete

echo "Backup complete: $(basename "$OUT") ($(wc -c < "$OUT") bytes), retention ${RETENTION}d"
