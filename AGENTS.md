# AGENTS.md

## Scope
This file governs `/Users/rcs/git/ssl-proxy/services/schema-migrator`.

## Project Shape
- Scala 3 sbt service using Cats Effect for the schema migrator runtime.
- The CLI discovers and applies ordered SQL files from the repository `sql/`
  tree.
- Postgres is expected to work locally with the split `sql/*` tree.
- Oracle support is JDBC-based and should be validated with connection-level
  checks unless an Oracle test target is explicitly available.

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
- Validate SQL from this directory: `sbt "run --sql-dir ../../sql validate"`
- List discovered SQL order: `sbt "run --sql-dir ../../sql list"`
- Dry-run apply: `sbt "run --sql-dir ../../sql --dry-run apply"`

