#!/usr/bin/env bash
# Logical restore verification for ChatBI.
#
# Default behaviour is non-destructive: the dump is restored into a scratch
# database, integrity checks run, and the scratch database is dropped. This is
# the path to use for drill/backup validation.
#
#   scripts/restore-postgres.sh backups/chatbi-20261026-030000.dump
#
# The script never touches the live database. To perform an actual recovery,
# stop the backend, create a new database, restore with --target-db, verify,
# then point CHATBI_DB_URL at it (documented in README).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-chatbi-prod}"

DUMP="${1:-}"
TARGET_DB="${2:-}"
[ -n "$DUMP" ] || { echo "usage: $0 <dump-file> [target-db]" >&2; exit 1; }
[ -f "$DUMP" ] || { echo "dump not found: $DUMP" >&2; exit 1; }
[ -f "$ENV_FILE" ] || { echo "missing env file: $ENV_FILE" >&2; exit 1; }

if [ -f "$DUMP.sha256" ]; then
    echo "Verifying checksum"
    (cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256")
fi

SCRATCH="${TARGET_DB:-chatbi_restore_verify_$(date +%s)}"

compose() {
    docker compose --project-name "$COMPOSE_PROJECT_NAME" \
        -f "$ROOT/compose.yml" --env-file "$ENV_FILE" "$@"
}

cleanup() {
    if [ -z "$TARGET_DB" ]; then
        compose exec -T postgres sh -c 'dropdb --if-exists -U "$POSTGRES_USER" "$SCRATCH_DB"' \
            >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

echo "Creating scratch database $SCRATCH"
compose exec -T -e SCRATCH_DB="$SCRATCH" postgres \
    sh -c 'createdb -U "$POSTGRES_USER" "$SCRATCH_DB"'

echo "Restoring $DUMP into $SCRATCH"
# Keep ACLs: the dump must reproduce the chatbi_ro view grants, and the role is
# cluster-wide so it exists for every scratch database. Ownership is skipped so
# the drill also works when the dump is restored by a different superuser.
compose exec -T -e SCRATCH_DB="$SCRATCH" postgres \
    sh -c 'exec pg_restore -U "$POSTGRES_USER" -d "$SCRATCH_DB" --no-owner' < "$DUMP"

query() {
    compose exec -T -e SCRATCH_DB="$SCRATCH" postgres \
        sh -c 'psql -U "$POSTGRES_USER" -d "$SCRATCH_DB" -tAc "$1"' -- "$1" | tr -d '\r'
}

fail=0
check_equals() {
    local label="$1" sql="$2" expected="$3" actual
    actual="$(query "$sql")"
    if [ "$actual" = "$expected" ]; then
        echo "PASS  $label = $actual"
    else
        echo "FAIL  $label expected $expected, got $actual"
        fail=1
    fi
}

check_at_least() {
    local label="$1" sql="$2" minimum="$3" actual
    actual="$(query "$sql")"
    if [ "$actual" -ge "$minimum" ] 2>/dev/null; then
        echo "PASS  $label = $actual"
    else
        echo "FAIL  $label expected >= $minimum, got $actual"
        fail=1
    fi
}

check_equals "failed flyway migrations" \
    "SELECT count(*) FROM flyway_schema_history WHERE NOT success" "0"
check_at_least "flyway migrations applied" \
    "SELECT count(*) FROM flyway_schema_history WHERE success" "1"
check_equals "seeded users present" \
    "SELECT count(*) FROM app_user WHERE username IN ('lisi','zhangsan','wangwu')" "3"
check_equals "six semantic views exist" \
    "SELECT count(*) FROM information_schema.views WHERE table_schema='public' AND table_name LIKE 'v\\_%'" "6"
check_equals "chatbi_ro SELECT grants are the six views only" \
    "SELECT count(*) FROM information_schema.table_privileges WHERE grantee='chatbi_ro' AND privilege_type='SELECT'" "6"
check_equals "chatbi_ro has no base-table grants" \
    "SELECT count(*) FROM information_schema.table_privileges p JOIN information_schema.tables t ON t.table_catalog=p.table_catalog AND t.table_schema=p.table_schema AND t.table_name=p.table_name WHERE p.grantee='chatbi_ro' AND t.table_type='BASE TABLE'" "0"

if [ "$fail" -ne 0 ]; then
    echo "Restore verification FAILED" >&2
    exit 1
fi
echo "Restore verification PASSED (scratch database $SCRATCH)"
