# AGENTS.md

## Scope
This file governs this `schema-migrator` repository checkout.

## Project Shape
- Scala 3 sbt service using Cats Effect for the schema migrator runtime.
- The CLI discovers and applies ordered SQL files from the repository `sql/`
  tree.
- Postgres is expected to work locally with the split `sql/*` tree.
- A small bundled web UI lives in `schema-migrator-ui/` (Vite + React). CI and
  local development may use the provided `docker-compose.yml` which builds
  the `backend`, `frontend`, and `traefik` services and an accompanying PostgreSQL
  service used by the UI/API for persisted migration targets.

## Guardrails
- Preserve deterministic ordering. Postgres order is extensions, schemas,
  types, tables, indexes, functions, views, cron pre-apply hooks,
  materialized_views, then cron jobs.
- Keep SQL application idempotent and retry-safe. Do not weaken schema-control
  hashing, locking, apply-log, rollback, or readiness behavior.
- Oracle support under `sql/oracle/` is deprecated and no longer active.
  Do not add new Oracle SQL or Oracle provider code.
- TiDB schema lives under `sql/tidb/` and is the authoritative sink target.
- Keep validation useful without requiring a live database where possible.
- Build every new dialog on `components/ui/Modal.tsx`; do not add another portal or focus trap.
- Leave `ConnectionForm.tsx` and `TargetFormPage.tsx` as single-step forms until a second real wizard flow justifies extracting them.

## Commands
- Run tests: `sbt test`
- List SQL files in apply order: `sbt "run --sql-dir ./sql list"`
- Validate SQL from this directory (no DB required): `sbt "run --sql-dir ./sql validate"`
- Dry-run apply (print SQL that would be executed): `sbt "run --sql-dir ./sql --dry-run apply"`
- Check DB connectivity: `sbt "run --sql-dir ./sql check-connection"`
- Apply migrations: `sbt "run --sql-dir ./sql apply"`
- Docker compose (local dev for UI + API + PostgreSQL): `docker-compose up --build`
