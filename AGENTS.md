# AGENTS.md

## Scope
This file governs this `schema-migrator` repository checkout.

## Project Shape
- Scala 3 sbt service using Cats Effect for the schema migrator runtime.
- The CLI discovers and applies ordered SQL files from the repository `sql/`
  tree.
- TiDB is the sole internal store for HTTP API state, target CRUD, runs,
  snapshots, patches, validation results, audit events, and encrypted target
  credentials.
- PostgreSQL remains supported only as an explicitly configured external
  schema-migration target using the split `sql/postgres` tree.
- A small bundled web UI lives in `schema-migrator-ui/` (Vite + React).

## Guardrails
- Preserve deterministic ordering. Postgres order is extensions, schemas,
  types, tables, indexes, functions, views, cron pre-apply hooks,
  materialized_views, then cron jobs.
- Never add a PostgreSQL or MongoDB internal-state fallback. `BEDROCK_STATE_DB_*`
  must continue to select the dedicated TiDB `schema_migrator` database with
  an explicit transport mode and a non-root account.
- Keep SQL application idempotent and retry-safe. Do not weaken schema-control
  hashing, locking, apply-log, rollback, or readiness behavior.
- Oracle support under `sql/oracle/` and the provider packages is deprecated
  compatibility material, not current usage guidance. Do not add new Oracle
  SQL or Oracle provider code.
- The parent repository's `sql/tidb/schema_migrator` manifest is authoritative
  for internal state. The application verifies it and never provisions DDL.
- Keep validation useful without requiring a live database where possible.
- Build every new dialog on `components/ui/Modal.tsx`; do not add another portal or focus trap.
- Leave `ConnectionForm.tsx` and `TargetFormPage.tsx` as single-step forms until a second real wizard flow justifies extracting them.

## Local Development
- Docker Compose may be used only as a local UI/API test harness against
  externally provisioned TiDB state and identity-provider endpoints:
  `docker-compose up --build`

## Commands
- Run tests: `sbt test`
- List PostgreSQL SQL files in apply order: `sbt "run --db-kind postgres --sql-dir ./sql/postgres list"`
- Validate PostgreSQL SQL (no DB required): `sbt "run --db-kind postgres --sql-dir ./sql/postgres validate"`
- Dry-run PostgreSQL apply (print SQL that would be executed): `sbt "run --db-kind postgres --sql-dir ./sql/postgres --dry-run apply"`
- Check PostgreSQL DB connectivity: `sbt "run --db-kind postgres --sql-dir ./sql/postgres check-connection"`
- Apply PostgreSQL migrations: `sbt "run --db-kind postgres --sql-dir ./sql/postgres apply"`
