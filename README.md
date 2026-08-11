# Schema Migrator

Schema Migrator is a Scala 3/Cats Effect service and CLI for discovering,
validating, planning and applying ordered SQL to external target databases. Its
HTTP API, target CRUD, encrypted credentials, runs, snapshots, patches and
audit state live only in the dedicated TiDB `schema_migrator` database.

PostgreSQL is supported as an external migration target. It is not the
service's internal state store. Oracle provider and SQL code remains for
deprecated compatibility and historical validation; do not use it as current
deployment guidance or add new Oracle material.

The parent platform architecture is documented in
[`docs/architecture.md`](../../docs/architecture.md).

## Responsibilities

- deterministic SQL discovery and manifest ordering
- offline validation and dry-run planning
- database connection checks and guarded apply/rollback flows
- PostgreSQL catalog drift analysis for external targets
- HTTP target, run, snapshot, patch, validation and audit APIs
- encrypted target credentials and TiDB-backed control state
- Keycloak or configured bearer-token authorization
- a Vite/React operator UI in `schema-migrator-ui/`

The service does not provision its own internal schema. The parent
repository's schema executor applies the checksummed
`sql/tidb/schema_migrator` manifest before this service starts.

## Internal state

The server requires:

| Variable | Purpose |
|---|---|
| `BEDROCK_STATE_DB_URL` | `jdbc:mysql://` TiDB URL selecting exactly `schema_migrator`, without inline credentials and with `sslMode=VERIFY_IDENTITY` |
| `BEDROCK_STATE_DB_USER` | Dedicated non-root TiDB account |
| `BEDROCK_STATE_DB_PASSWORD` | TiDB account password |
| `BEDROCK_STATE_DB_POOL_SIZE` | Pool size, default `10` |

Startup rejects loopback state-store hosts, TiDB older than 8.5, a non-UTC
session, a wrong database and missing/mismatched manifest readiness. Provide
the TiDB CA through the JVM truststore used by the deployment.

## External targets

The normal external target is PostgreSQL:

```bash
sbt "run --db-kind postgres \
  --database-url jdbc:postgresql://db.example:5432/application \
  --sql-dir ./sql/postgres check-connection"
```

Target credentials entered through the API are encrypted before storage in
TiDB. Connection-test hosts are restricted by
`BEDROCK_DB_TEST_ALLOWED_HOSTS`. Do not embed usernames/passwords in JDBC URLs
when a separate credential field exists.

TiDB/MySQL target support also exists for explicit migration operations.
Oracle flags and providers are deprecated compatibility surfaces, not a
recommended target workflow.

## CLI

Commands are available through sbt during development:

```bash
sbt "run --db-kind postgres --sql-dir ./sql/postgres list"
sbt "run --db-kind postgres --sql-dir ./sql/postgres validate"
sbt "run --db-kind postgres --sql-dir ./sql/postgres --dry-run apply"
sbt "run --db-kind postgres \
  --database-url jdbc:postgresql://db.example:5432/application \
  --sql-dir ./sql/postgres check-connection"
```

Important options:

| Option | Purpose |
|---|---|
| `--db-kind` | `postgres`, `tidb`/`mysql`, or deprecated `oracle` |
| `--sql-dir` | SQL root for discovery |
| `--customer` | Optional single-directory customer overlay |
| `--database-url` | Explicit external target URL |
| `--dry-run` | Print/plan without applying |
| `--connect-retries` | Bounded connection retries |
| `--json` | Machine-readable output |

The PostgreSQL apply order is extensions, schemas, types, tables, indexes,
functions, views, cron pre-apply hooks, materialized views, then cron jobs.
Preserve deterministic ordering, checksums, locks and apply logs.

## HTTP server

Server configuration uses the `BEDROCK_*` family:

- `BEDROCK_HTTP_HOST`, `BEDROCK_HTTP_PORT`, `BEDROCK_CORS_ORIGINS`
- `BEDROCK_ENCRYPT_KEY` for AES-256-GCM target credential encryption
- `BEDROCK_JWT_SECRET` and `BEDROCK_API_BEARER_TOKEN`
- `BEDROCK_DEV_AUTH_ENABLED`/`BEDROCK_DEV_AUTH_SECRET` for explicit development
  auth only
- `BEDROCK_KEYCLOAK_*` for production RS256 verification
- `BEDROCK_PATCH_STAGE_DIR` and `BEDROCK_REPO_CACHE_DIR`
- `BEDROCK_DB_TEST_ALLOWED_HOSTS`

The server fails closed when required auth, encryption or state-store
configuration is missing.

## Kubernetes runtime

The parent repository owns the Schema Migrator Kubernetes resources in its
Kustomize app-stack base and environment slices. Argo CD reconciles those
resources from the parent repository's `main` branch.

The in-cluster Keycloak uses its own deployment-created `keycloak` database.
It is separate from the four canonical application manifests.

## Local development

Docker Compose may be used only as a local service/UI test harness, with TiDB
and identity endpoints provisioned outside the harness:

```bash
docker-compose up --build
```

## Build and test

```bash
sbt compile
sbt test
sbt assembly
cd schema-migrator-ui
bun run test
bun run build
```

Keep database validation useful without a live target where possible. Do not
commit `.bsp`, `.metals`, build output, UI dependencies or credentials.
