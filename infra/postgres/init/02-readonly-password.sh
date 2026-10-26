#!/bin/sh
# Runs after 01-readonly.sql on a fresh database. The .sql file creates the
# role with the local demo password; production supplies a strong random value
# via CHATBI_READONLY_DB_PASSWORD. psql's :'var' quoting keeps the password out
# of SQL injection reach.
set -eu

if [ -n "${CHATBI_READONLY_DB_PASSWORD:-}" ]; then
    psql -v ON_ERROR_STOP=1 \
         --username "$POSTGRES_USER" \
         --dbname "$POSTGRES_DB" \
         -v ro_password="$CHATBI_READONLY_DB_PASSWORD" <<'EOSQL'
ALTER ROLE chatbi_ro WITH PASSWORD :'ro_password';
EOSQL
    echo "01-readonly: chatbi_ro password set from CHATBI_READONLY_DB_PASSWORD"
else
    echo "01-readonly: CHATBI_READONLY_DB_PASSWORD not set; keeping demo password (local only)"
fi
