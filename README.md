# Schema Migrator

**Unified schema migrator for Postgres and Oracle**

A deterministic, retry-safe, functional schema migration engine built on **Scala 3**, **Cats Effect**, and **Doobie**. It discovers ordered SQL files, validates dependencies, locks migrations, tracks an apply log, supports rollbacks, and emits machine-readable JSON—all without requiring a live database for validation.

---

## Table of Contents

1. [What it solves](#what-it-solves)
2. [Who should use it](#who-should-use-it)
3. [Key capabilities](#key-capabilities)
4. [Benefits](#benefits)
5. [Quick start](#quick-start)
6. [Commands](#commands)
7. [Configuration](#configuration)
8. [Deterministic ordering](#deterministic-ordering)
9. [Validation without a database](#validation-without-a-database)
10. [Oracle setup](#oracle-setup)
11. [Building and testing](#building-and-testing)

---

## What it solves

Managing database schemas across heterogeneous environments (Postgres + Oracle) with large, evolving SQL trees is hard. Schema Migrator solves five concrete problems:

1. **Unified interface** — one CLI for Postgres and Oracle, not two separate tools with divergent workflows.
2. **Deterministic ordering** — migrations run in a guaranteed sequence, eliminating “works on my branch” failures caused by implicit file order.
3. **Idempotent, retry-safe execution** — built-in locking, apply logs, and schema-control hashing make re-runs and restarts safe.
4. **Early failure detection** — validate SQL parsing, dependency balance, and rollback completeness locally, before touching production.
5. **Operational visibility** — status reporting, readiness checks, and JSON output integrate cleanly into CI/CD and runbooks.

---

## Who should use it

- **Backend & data platform engineers** deploying services backed by Postgres and/or Oracle.
- **DevOps / platform teams** building shared migration infrastructure that spans multiple databases.
- **Teams migrating from Oracle to Postgres (or vice versa)** who need a consistent workflow during the transition.
- **Organizations with strict governance requirements** that need auditable apply logs, locks, and schema-control hashes.

---

## Key capabilities

| Capability | Details |
|---|---|
| **Multi-engine** | Postgres and Oracle via Doobie JDBC; can validate without a live database. |
| **Deterministic file ordering** | Extensions → schemas → types → tables → indexes → functions → views → cron pre-apply hooks → materialized views → cron jobs. |
| **Idempotent applies** | Schema-controlled object hashing + apply log + pessimistic locking. |
| **Rollback support** | Execute tracked rollback SQL for any applied object. |
| **Dependency validation** | Detects cross-object dependencies (e.g., views before tables, functions before use) before execution. |
| **Rollback completeness** | Validates that every tracked object has a counterpart rollback. |
| **Local validation** | Parse and verify SQL trees without a database connection. |
| **Readiness checks** | Verify schema readiness; optionally fail the build when not ready. |
| **Connection validation** | Test JDBC/TNS connectivity and fail fast. |
| **Retry & backoff** | Configurable retries with backoff for transient connection failures. |
| **JSON reporting** | Machine-readable output for CI, dashboards, and automation. |
| **Oracle wallet/JDBC** | Native Oracle wallet support via TNS admin directories and password files. |

---

## Benefits

- **Safety** — Locks, apply logs, and schema-control hashing prevent double-apply, lost migrations, and drift.
- **Speed** — Catch syntax errors, missing rollbacks, and dependency cycles locally in seconds.
- **Consistency** — The same ordered plan runs across dev, staging, and production.
- **Observability** — Status, readiness, and JSON reporters give teams real-time visibility into schema state.
- **Portability** — One tool, one model; switch databases without changing your migration workflow.
- **Functional runtime** — Cats Effect gives referential transparency, structured concurrency, and predictable resource cleanup.

---

## Quick start

```bash
# List SQL files in apply order (no database required)
sbt "run --sql-dir ./sql list"

# Validate without connecting to a database
sbt "run --sql-dir ./sql validate"

# Dry-run: print SQL that would be executed
sbt "run --sql-dir ./sql --dry-run apply"

# Check connection
sbt "run --sql-dir ./sql check-connection"

# Apply migrations
sbt "run --sql-dir ./sql apply"
```

For Oracle, set the database kind and supply credentials or environment variables:

```bash
SCHEMA_MIGRATOR_DB_KIND=oracle \
ORACLE_JDBC_URL=... \
ORACLE_USER=sys \
ORACLE_PASS_FILE=/run/secrets/oracle_password \
  sbt "run --sql-dir ./sql/oracle apply"
```

---

## Commands

| Command | Description |
|---|---|
| `apply` | Discover, validate, lock, and apply pending SQL objects in deterministic order. |
| `validate` | Parse SQL files and validate dependencies and rollback completeness without executing. |
| `list` | Print discovered SQL files and objects in the order they would be applied. |
| `status` | Show current schema control object status (applied, pending, hashes). |
| `rollback <object>` | Execute rollback SQL for a tracked, applied object. |
| `ready [--strict]` | Check schema readiness; with `--strict`, exit non-zero if not ready. |
| `check-connection` | Open and validate a database connection; useful for pre-flight checks. |

If no command is given, `apply` is used as the default.

---

## Configuration

### CLI flags

| Flag | Default | Description |
|---|---|---|
| `--db-kind` | `postgres` | `postgres` or `oracle`. |
| `--sql-dir` | `./sql` (or `./sql/oracle`) | Root directory containing SQL files. |
| `--database-url` | — | JDBC URL (Oracle) or `postgres://` URL (Postgres). |
| `--dry-run` | `false` | Print SQL without executing. |
| `--verbose` | `false` | Echo each statement before running. |
| `--continue-on-error` | `false` | Continue processing after SQL errors. |
| `--connect-retries` | `0` | Number of connection retry attempts. |
| `--connect-retry-backoff` | `2` | Base retry backoff in seconds. |
| `--oracle-wallet` | — | Oracle wallet or TNS admin directory. |
| `--oracle-tns-alias` | — | Oracle TNS alias. |
| `--oracle-user` | — | Oracle username. |
| `--oracle-pass-file` | — | File containing Oracle password. |
| `--json` | `false` | Print machine-readable JSON output. |

### Environment variables

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | Postgres database URL. |
| `ORACLE_JDBC_URL` | Oracle JDBC URL. |
| `SCHEMA_MIGRATOR_DB_KIND` | `postgres` or `oracle`. |
| `ORACLE_USER` | Oracle username. |
| `ORACLE_CONN` | Oracle TNS alias / connection name. |
| `ORACLE_PASS_FILE` | File path for Oracle password. |
| `TNS_ADMIN` | Oracle wallet / TNS admin directory. |

---

## Deterministic ordering

Schema Migrator discovers SQL files from the repository tree and applies them in a fixed, version-agnostic order. This eliminates non-determinism caused by filesystem traversal, developer machine differences, or OS-dependent path sorting. The standard order is:

1. Extensions
2. Schemas
3. Types
4. Tables
5. Indexes
6. Functions
7. Views
8. Cron pre-apply hooks
9. Materialized views
10. Cron jobs

Custom phases can be added by extending the `Phase` set in the engine.

---

## Validation without a database

`validate` runs the full parser, canonicalizer, and dependency graph builder locally:

- **Syntax & parsing** — detects malformed SQL and unsupported constructs.
- **Dependency validation** — ensures references between objects are resolvable and ordered.
- **Rollback completeness** — verifies every tracked object has a corresponding rollback path.

This means teams can gate merges on validation alone, long before a shared database is available.

---

## Oracle setup

Oracle connections are JDBC-based and support wallet/TNS configurations:

- `.wallet` or TNS admin directories via `--oracle-wallet` or `TNS_ADMIN`.
- TNS alias via `--oracle-tns-alias` or `ORACLE_CONN`.
- Username via `--oracle-user` or `ORACLE_USER`.
- Password files via `--oracle-pass-file` or `ORACLE_PASS_FILE`.

Required runtime JARs are included in the build (ojdbc11, oraclepki, osdt_core, osdt_cert).

---

## Building and testing

```bash
# Build
sbt compile

# Run tests
sbt test

# Package
sbt assembly
```

The project targets Scala 3.3.6 and uses Cats Effect 3.6.3, Doobie 1.0.0-RC10, and Decline 2.5.0.