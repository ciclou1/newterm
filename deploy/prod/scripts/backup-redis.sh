#!/usr/bin/env bash
# Snapshot the Redis dataset. AOF already persists every write; this produces a
# point-in-time RDB copy for off-volume retention.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-chatbi-prod}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/redis-$STAMP.rdb"

[ -f "$ENV_FILE" ] || { echo "missing env file: $ENV_FILE" >&2; exit 1; }
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

RETENTION="$(grep -E '^BACKUP_RETENTION_DAYS=' "$ENV_FILE" | tail -1 | cut -d= -f2 || true)"
RETENTION="${RETENTION:-14}"

compose() {
    docker compose --project-name "$COMPOSE_PROJECT_NAME" \
        -f "$ROOT/compose.yml" --env-file "$ENV_FILE" "$@"
}

# The snapshot is written inside the container and streamed back over stdout.
# Passing container paths through `docker exec -e` breaks on Git Bash/MSYS,
# which rewrites POSIX paths into Windows paths before they reach docker.
compose exec -T redis sh -c '
    set -e
    f="/tmp/chatbi-backup.rdb"
    redis-cli --rdb "$f" >/dev/null 2>&1
    cat "$f"
    rm -f "$f"
' > "$OUT"
chmod 600 "$OUT"
[ -s "$OUT" ] || { echo "redis snapshot is empty" >&2; rm -f "$OUT"; exit 1; }

find "$BACKUP_DIR" -maxdepth 1 -type f -name 'redis-*.rdb' -mtime +"$RETENTION" -print -delete
echo "Redis snapshot complete: $(basename "$OUT") ($(wc -c < "$OUT") bytes), retention ${RETENTION}d"
