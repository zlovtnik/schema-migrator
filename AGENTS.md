# AGENTS.md

## Scope
This file governs `/Users/rcs/git/wiretrap/schema-migrator`.

## Project Shape
- Scala 3 sbt service using Cats Effect for the schema migrator runtime.
- The CLI discovers and applies ordered SQL files from the repository `sql/`
  tree.
- Postgres is expected to work locally with the split `sql/*` tree.
- Oracle support is JDBC-based and should be validated with connection-level
  checks unless an Oracle test target is explicitly available.
 - A small bundled web UI lives in `schema-migrator-ui/` (Vite + React). CI and
   local development may use the provided `docker-compose.yml` which builds
   the `backend` and `nginx` services and an accompanying `mongo` service used
   by the UI/API for persisted migration targets.

## Guardrails
- Preserve deterministic ordering. Postgres order is extensions, schemas,
  types, tables, indexes, functions, views, cron pre-apply hooks,
  materialized_views, then cron jobs.
- Keep SQL application idempotent and retry-safe. Do not weaken schema-control
  hashing, locking, apply-log, rollback, or readiness behavior.
- Keep Oracle wallet/JDBC handling inside this service or
  `services/zig-coordinator`; do not add Oracle access to proxy, sync-plane, or
  sensor code.
- Keep validation useful without requiring a live database where possible.

## Commands
- Run tests: `sbt test`
 - List SQL files in apply order: `sbt "run --sql-dir ./sql list"`
 - Validate SQL from this directory (no DB required): `sbt "run --sql-dir ./sql validate"`
 - Dry-run apply (print SQL that would be executed): `sbt "run --sql-dir ./sql --dry-run apply"`
 - Check DB connectivity: `sbt "run --sql-dir ./sql check-connection"`
 - Apply migrations: `sbt "run --sql-dir ./sql apply"`
 - Docker compose (local dev for UI + API + mongo): `docker-compose up --build`

