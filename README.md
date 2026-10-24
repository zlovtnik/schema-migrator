# Schema Migrator

Schema Migrator is a Scala 3/Cats Effect service and CLI for discovering,
validating, planning and applying ordered SQL to external target databases. Its
HTTP API, target CRUD, encrypted credentials, runs, snapshots, patches and
audit state live in the `schema_migrator` schema of the external PostgreSQL
`sync` database.

Catalog drift checks compare selected object definitions and table presence;
they do not compare column definitions or nullability. A clean drift report
cannot rule out column drift. CLI dry-run output collapses whitespace and
shortens SQL longer than 120 characters, so review the complete source files
alongside the preview. Offline validation does not establish target execution
safety.

PostgreSQL is also supported as an external migration target. Oracle provider
and SQL code remains for deprecated compatibility and historical validation;
do not use it as current
deployment guidance or add new Oracle material.

The parent platform architecture is documented in
[`docs/architecture.md`](../../docs/architecture.md).

## Responsibilities

- deterministic SQL discovery and manifest ordering
- offline validation and dry-run planning
- database connection checks and guarded apply/rollback flows
- PostgreSQL catalog drift analysis for external targets
- HTTP target, run, snapshot, patch, validation and audit APIs
- encrypted target credentials and PostgreSQL-backed control state
- Keycloak or configured bearer-token authorization
- a Vite/React operator UI and optional Electron desktop shell in
  [`schema-migrator-ui/`](schema-migrator-ui/)

The service does not provision its own internal schema. The parent
repository's schema executor applies the checksummed
[`sql/postgres/schema_migrator`](../../sql/postgres/schema_migrator) manifest
before this service starts. The bundled `sql/` files are target-migration
inputs; they are not the authoritative provisioning source for the parent
platform's runtime schemas.

## Source map

| Path | Responsibility |
|---|---|
| [`src/main/scala/com/sslproxy/schema/cli/CliOpts.scala`](src/main/scala/com/sslproxy/schema/cli/CliOpts.scala) | CLI commands, flags and environment defaults |
| [`src/main/scala/com/sslproxy/schema/config/MigratorConfig.scala`](src/main/scala/com/sslproxy/schema/config/MigratorConfig.scala) | Target and HTTP server configuration validation |
| [`src/main/scala/com/sslproxy/schema/server/`](src/main/scala/com/sslproxy/schema/server/) | Authenticated HTTP routes and target operations |
| [`src/main/scala/com/sslproxy/schema/store/StateDatabase.scala`](src/main/scala/com/sslproxy/schema/store/StateDatabase.scala) | Internal PostgreSQL connection and schema-contract verification |

## Internal state

The server requires:

| Variable | Purpose |
|---|---|
| `BEDROCK_STATE_DB_URL` | `jdbc:postgresql://` URL selecting `sync` with `currentSchema=schema_migrator`, without inline credentials; production uses verified TLS |
| `BEDROCK_STATE_DB_USER` | Dedicated non-root PostgreSQL account |
| `BEDROCK_STATE_DB_PASSWORD` | PostgreSQL account password |
| `BEDROCK_STATE_DB_POOL_SIZE` | Pool size, default `10` |

Startup rejects loopback state-store hosts, PostgreSQL older than 14, a non-UTC
session, a wrong database and missing/mismatched manifest readiness. Provide
the PostgreSQL CA through the JDBC TLS configuration used by the deployment.

## External targets

The normal external target is PostgreSQL:

```bash
sbt "run --db-kind postgres \
  --database-url jdbc:postgresql://db.example:5432/application \
  --sql-dir ./sql/postgres check-connection"
```

Target credentials entered through the API are encrypted before storage in
PostgreSQL. Connection-test hosts are restricted by
`BEDROCK_DB_TEST_ALLOWED_HOSTS`. Do not embed usernames/passwords in JDBC URLs
when a separate credential field exists.

TiDB/MySQL target support also exists for explicit migration operations.
Oracle flags and providers are deprecated compatibility surfaces, not a
recommended target workflow.

## CLI

Offline discovery, validation, and preview work from a standalone checkout with
JDK 21 and sbt, without a database or the parent platform. The sbt version is
pinned in [`project/build.properties`](project/build.properties):

```bash
git clone https://github.com/zlovtnik/schema-migrator.git
cd schema-migrator
```

Commands are available through sbt during development. In the parent workspace,
first change to `apps/schema-migrator`:

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
configuration is missing. The encryption key must be base64 encoding of 32
bytes. Once those environment variables and the external schema are ready,
start the API from this directory:

```bash
sbt "run serve"
```

The default bind address is `127.0.0.1:8080`. `/api/health` is an
unauthenticated process-health endpoint; successful startup has already
verified the internal schema contract. It does not validate every external
migration target.

## Kubernetes runtime

The parent repository owns the Schema Migrator Kubernetes resources in its
Kustomize app-stack base and environment slices. Argo CD reconciles those
resources from the parent repository's `main` branch.

The in-cluster Keycloak uses the isolated `keycloak` schema in the external
PostgreSQL `sync` database. Its authoritative manifest is
[`sql/postgres/keycloak`](../../sql/postgres/keycloak).

## Local development

Run the backend as above against externally provisioned PostgreSQL state and
identity endpoints. In a second terminal, start the UI:

```bash
cd schema-migrator-ui
bun install --frozen-lockfile
bun run dev
```

Vite listens on port `5173` and proxies `/api` to `http://localhost:8080`.
Set `VITE_API_PROXY_TARGET` when the backend is elsewhere. Configure browser
identity settings using the UI's [environment example](schema-migrator-ui/.env.example).
The checked-in Compose harness retains legacy TiDB truststore settings; it
requires reconciliation with the current PostgreSQL configuration before use.

## Build and test

```bash
sbt compile
sbt test
sbt assembly
cd schema-migrator-ui
bun install --frozen-lockfile
bun run lint
bun run typecheck
bun run test
bun run build
```

Keep database validation useful without a live target where possible. Do not
commit `.bsp`, `.metals`, build output, UI dependencies or credentials.
