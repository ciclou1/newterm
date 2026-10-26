# ChatBI production deployment

This directory is the hardened deployment target. The repository-root
`docker-compose.yml` remains the local demo stack (fixed demo accounts, ports on
`0.0.0.0`); do not use it for anything reachable from a network.

```
deploy/prod/
  compose.yml               production stack (only 80/443 published)
  .env.example              every required variable, no real values
  edge/                     non-root nginx TLS terminator + static proxy
  frontend/                 non-root static nginx image
  prometheus/               scrape config + alert rules
  grafana/                  provisioned datasource + dashboard
  scripts/                  TLS cert, PostgreSQL/Redis backup and restore
  certs/                    ignored; mounted TLS material
  backups/                  ignored; dumps and RDB snapshots
```

## 1. First deployment

```bash
cd deploy/prod
cp .env.example .env
chmod 600 .env
```

Fill every blank value. Compose uses `${VAR:?message}`, so startup fails rather
than falling back to a shared default when anything is missing.

### Required secrets

| Variable | How to generate | Notes |
|---|---|---|
| `CHATBI_JWT_SECRET` | `openssl rand -base64 48` | HS256, ≥32 bytes; docker/prod refuse the bundled dev value |
| `POSTGRES_PASSWORD` | `openssl rand -base64 32` | owner account |
| `CHATBI_READONLY_DB_PASSWORD` | `openssl rand -base64 32` | `chatbi_ro`; must differ from the owner password |
| `CHATBI_REDIS_PASSWORD` | `openssl rand -base64 32` | `requirepass`; Redis has no host port |
| `CHATBI_BOOTSTRAP_ADMIN_PASSWORD` | `openssl rand -base64 24` | ≥12 chars; used once on first start |
| `CHATBI_METRICS_TOKEN` | `openssl rand -hex 32` | only accepted on `/actuator/prometheus` |
| `GRAFANA_ADMIN_PASSWORD` | `openssl rand -base64 24` | Grafana has no published port by default |
| `CHATBI_PUBLIC_HOST` | your DNS name | must match the TLS certificate |

Base64 output contains no quotes, so the init script can safely pass it to
`ALTER ROLE ... PASSWORD :'var'`.

### Bootstrap administrator

The first start replaces the seeded demo password for
`CHATBI_BOOTSTRAP_ADMIN_USERNAME` (default `lisi`). The application also refuses
to start if seeded demo passwords remain and this variable is empty.

Immediately after the stack is healthy, rotate the bootstrap password to one
known only to the operator:

```bash
TOKEN=$(curl -sk https://chatbi.example.com/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"lisi","password":"<bootstrap password>"}' | jq -r .token)

curl -sk https://chatbi.example.com/api/auth/password \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"currentPassword":"<bootstrap password>","newPassword":"<new operator password>"}'
```

Then remove `CHATBI_BOOTSTRAP_ADMIN_PASSWORD` from `.env` and recreate the
backend. The bootstrap value is only consumed when a demo/unset password is
still in place.

### Model key

Inject the model gateway key only through the environment:

```bash
# edit .env, never a tracked file:
CHATBI_AI_ENABLED=true
CHATBI_AI_API_KEY=...
CHATBI_AI_BASE_URL=https://gateway.example.com
CHATBI_AI_MODEL=deepseek-flash
```

Then `docker compose --project-name chatbi-prod -f compose.yml up -d backend`.
With `CHATBI_AI_ENABLED=true` and no key the application fails at startup
instead of silently using the demo planner. Keys are never written to images,
logs, or the audit table. `docker compose config` prints resolved secrets — do
not paste its output into tickets.

### TLS

Provide an operator-issued certificate or generate a self-signed pair for
verification:

```bash
scripts/gen-self-signed-cert.sh chatbi.example.com
# or mount an existing pair:
CHATBI_TLS_CERT=/etc/chatbi/tls/fullchain.pem
CHATBI_TLS_KEY=/etc/chatbi/tls/privkey.pem
```

Only `80` (301 redirect) and `443` are published. Certificate private keys
should be `0600`.

## 2. Start and verify

```bash
GIT_COMMIT=$(git rev-parse --short HEAD) \
  docker compose --project-name chatbi-prod -f compose.yml up -d --build
docker compose --project-name chatbi-prod -f compose.yml ps

curl -k https://chatbi.example.com/healthz
curl -k -o /dev/null -w '%{http_code}\n' https://chatbi.example.com/actuator/health   # 404 from edge
curl -k -o /dev/null -w '%{http_code}\n' https://chatbi.example.com/api/health/ai     # 401 anonymous
curl -k -o /dev/null -w '%{http_code}\n' https://chatbi.example.com/actuator/prometheus # 404 from edge
```

Internal checks:

```bash
# anonymous cannot read metrics or AI health
docker compose ... exec -T backend wget -qO- http://127.0.0.1:8080/actuator/prometheus   # 401
docker compose ... exec -T backend wget -qO- http://127.0.0.1:8080/api/health/ai        # 401

# scrape token only unlocks metrics
TOKEN=... curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $CHATBI_METRICS_TOKEN" \
  http://127.0.0.1:8080/actuator/prometheus                                              # 200
```

PostgreSQL, Redis, Prometheus and Grafana publish no host ports. Backend,
PostgreSQL and Redis sit on the `internal` network (no outbound route); the
backend also joins `egress` solely to reach the model gateway.

Grafana is internal-only. To open it locally:

```bash
docker compose -f compose.yml -f compose.grafana-port.yml up -d grafana
# http://127.0.0.1:3000 — loopback only
```

## 3. Backup and restore

```bash
scripts/backup-postgres.sh          # custom-format dump + sha256, same-dir
scripts/backup-redis.sh             # point-in-time RDB copy
```

Both honour `BACKUP_RETENTION_DAYS` (default 14). Schedule them from the host:

```cron
15 2 * * * cd /opt/chatbi/deploy/prod && scripts/backup-postgres.sh >> backups/backup.log 2>&1
30 2 * * * cd /opt/chatbi/deploy/prod && scripts/backup-redis.sh >> backups/backup.log 2>&1
```

Restore verification is **non-destructive**: the dump is loaded into a scratch
database, integrity checks run, and the scratch database is dropped.

```bash
scripts/restore-postgres.sh backups/chatbi-20261026-030000.dump
# PASS flyway migrations applied = 6
# PASS seeded users present = 3
# PASS six semantic views exist = 6
# PASS chatbi_ro SELECT grants are the six views only = 6
# PASS chatbi_ro has no base-table grants = 0
```

For an actual disaster recovery, restore into a new database via the optional
second argument, verify it, then point `CHATBI_DB_URL` /
`CHATBI_QUERY_DB_URL` at it and recreate the backend. Keep the old database
until the new one has served traffic successfully.

Redis persists with AOF (`appendfsync everysec`) in the `redisdata` volume, and
the RDB snapshot provides an off-volume point-in-time copy.

## 4. Observability

- Prometheus scrapes `backend:8080/actuator/prometheus` with
  `CHATBI_METRICS_TOKEN`; the token only grants `ROLE_METRICS` on that path.
- Alert rules (`prometheus/alerts.yml`): backend down, PostgreSQL/Redis down,
  5xx rate >5%, query P95 >10s, LLM P95 near the 60s deadline, LLM failure
  rate >20%, retry storm, **audit fail-closed**, quota-rejection spike, and
  rate-limit spike.
- Grafana provisions the `ChatBI Production` dashboard at startup.
- Backend logs are JSON (`logstash` formatter) under the `prod` profile and
  include `requestId`; the edge writes JSON access logs with the same
  `$request_id` and forwards it as `X-Request-ID`.

Correlate an incident:

```bash
docker compose logs --since 15m backend | jq 'select(.requestId=="<id>")'
```

### SLOs

| SLI | Target | Alert |
|---|---|---|
| Availability (non-5xx / total) | ≥ 99.5% monthly | `ChatBiHigh5xxRate` |
| Query latency P95 | < 10s | `ChatBiQueryP95High` |
| LLM failure rate | < 20% over 5m | `ChatBiLlmFailureRate` |
| Audit write success | 100% | `ChatBiAuditFailClosed` |
| Dependency reachability | postgres/redis up | `ChatBiPostgresDown` / `ChatBiRedisDown` |

## 5. Operations

### Secret rotation

**JWT** — generate a new value, update `.env`, recreate the backend. All
existing tokens become invalid; users log in again. Do this immediately if a
secret may have leaked.

**Metrics token** — update `CHATBI_METRICS_TOKEN` and recreate both `backend`
and `prometheus` (Prometheus rewrites `/tmp/metrics_token` at startup).

**Model key** — update `CHATBI_AI_API_KEY`, recreate the backend. No image
rebuild is needed.

**Database/Redis passwords** — update `.env`, then:

```bash
# owner + read-only role
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "ALTER ROLE \"$POSTGRES_USER\" PASSWORD '$NEW_OWNER_PASSWORD';" \
  -c "ALTER ROLE chatbi_ro PASSWORD '$NEW_READONLY_PASSWORD';"
# redis (authenticate with the old password first)
docker compose exec -T redis redis-cli -a "$OLD_REDIS_PASSWORD" CONFIG SET requirepass "$NEW_REDIS_PASSWORD"
docker compose up -d --force-recreate backend redis
```

### Model failure degradation

Default `CHATBI_AI_ALLOW_DEMO_FALLBACK=false`: a failing or timed-out model
returns `ERROR` rather than a plausible-looking rule-engine answer. If the
business accepts degraded answers during a gateway incident, set it to `true`
and recreate the backend; responses are then marked `degraded=true` and the
mode is recorded in metrics/audit. Revert when the gateway recovers. The 60s
`CHATBI_AI_TOTAL_TIMEOUT_SECONDS` budget bounds every request regardless of
retries.

### Container hardening summary

- Backend/edge/frontend run as non-root (`chatbi` / nginx UID 101).
- All services: `no-new-privileges`, JSON log rotation (10 MB × 3),
  `restart: unless-stopped`, memory/CPU limits.
- Backend/edge/frontend use read-only root filesystems with `/tmp` tmpfs.
- Edge overwrites `X-Forwarded-For`/`X-Real-IP` and rate-limits login, chat and
  general API traffic.
- No `dependency:go-offline`: a single bounded Maven `package -DskipTests`
  with a BuildKit `~/.m2` cache mount builds the backend image.
- Every base/runtime image is digest-pinned. For a registry mirror or an
  air-gapped builder, override `*_IMAGE` in `.env` (see `.env.example`); the
  digest must stay identical or the build is no longer reproducible.

## 6. Known limits

- Rate limiting and login lockout are per instance (single backend replica
  assumed). Quota reservations are Redis-backed.
- The `hash` embedding provider is lexical, not semantic; switching providers
  needs a Flyway migration for `semantic_embedding.embedding` and a reindex.
- Prometheus/Grafana data volumes are local; ship them off-host if the host
  itself is the failure domain.
