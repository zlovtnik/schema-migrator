# Schema Migrator

**Introducing the Unified Schema Migrator for Postgres and Oracle**

Experience a powerful, deterministic, retry-safe schema migration engine built with **Scala 3**, **Cats Effect**, and **Doobie**. This tool efficiently discovers ordered SQL files, validates dependencies, locks migrations, tracks application logs, supports rollbacks, and emits machine-readable JSON, all without the need for a live database for validation.

---

## Table of Contents

1. [What It Solves](#what-it-solves)
2. [Who Should Use It](#who-should-use-it)
3. [Key Capabilities](#key-capabilities)
4. [Benefits](#benefits)
5. [Quick Start](#quick-start)
6. [Commands](#commands)
7. [Configuration](#configuration)
8. [Deterministic Ordering](#deterministic-ordering)
9. [Validation Without a Database](#validation-without-a-database)
10. [Oracle Setup](#oracle-setup)
11. [Building and Testing](#building-and-testing)

---

## What It Solves

Managing database schemas across diverse environments (Postgres and Oracle) with complex SQL trees is no small feat. Schema Migrator directly addresses five critical issues:

1. **Unified Interface** — Utilize a single CLI for both Postgres and Oracle, eliminating the hassle of juggling two different tools.
2. **Deterministic Ordering** — Migrations execute in a guaranteed sequence, putting an end to unpredictable failures related to file order.
3. **Idempotent, Retry-Safe Execution** — Built-in locking mechanisms, application logs, and schema-control hashing ensure that re-runs and restarts are entirely safe.
4. **Early Failure Detection** — Validate SQL parsing, dependency balance, and rollback completeness locally, prior to impacting production environments.
5. **Operational Visibility** — Enjoy seamless integration for status reporting, readiness checks, and JSON output into your CI/CD pipelines and runbooks.

---

## Who Should Use It

- **Backend and Data Platform Engineers** deploying services with Postgres and/or Oracle.
- **DevOps and Platform Teams** constructing a cohesive migration infrastructure that spans multiple databases.
- **Teams Transitioning from Oracle to Postgres (or vice versa)** needing a consistent workflow throughout migration.
- **Organizations with Stringent Governance Requirements** demanding auditable application logs, locks, and schema-control hashes.

---

## Key Capabilities

| Capability                  | Details                                                                 |
|-----------------------------|-------------------------------------------------------------------------|
| **Multi-Engine**            | Effortlessly supports Postgres and Oracle via Doobie JDBC; validates without a live database. |
| **Deterministic File Ordering** | Ensure a clear organization of migrations covering extensions, schemas, types, tables, indexes, functions, views, cron pre-apply hooks, materialized views, and cron jobs. |
| **Idempotent Applies**      | Leverage schema-controlled object hashing, application logs, and pessimistic locking for error-free migrations. |
| **Rollback Support**        | Execute tracked rollback SQL for any applied object with confidence.    |
| **Dependency Validation**    | Automatically detect cross-object dependencies before execution to prevent failures. |
| **Rollback Completeness**    | Guarantee that every tracked object has a corresponding rollback, maintaining consistency. |
| **Local Validation**        | Assertively parse and verify SQL trees without relying on a database connection. |
| **Readiness Checks**       | Validate schema readiness, with the flexibility to fail the build if not ready. |
| **Connection Validation**   | Quickly test JDBC/TNS connectivity and fail fast upon errors.            |
| **Retry and Backoff**      | Configurable retries with backoff cater for transient connection failures. |
| **JSON Reporting**          | Provide machine-readable outputs for CI, dashboards, and automation integration. |
| **Oracle Wallet/JDBC**     | Efficiently utilize native Oracle wallet support through TNS admin directories and password files. |

---

## Benefits

- **Safety** — Built-in locks, application logs, and schema-control hashing thwart double applications, lost migrations, and schema drift.
- **Speed** — Instantly identify syntax errors, missing rollbacks, and dependency cycles locally within seconds.
- **Consistency** — Ensure that the same ordered migration plan is executed seamlessly across development, staging, and production environments.
- **Observability** — Obtain real-time visibility into schema state through status updates, readiness checks, and JSON reports.
- **Portability** — Use a single tool and approach that allow for effortless switching between databases without changing your migration workflow.
- **Functional Runtime** — The use of Cats Effect guarantees referential transparency, structured concurrency, and reliable resource cleanup.

---

## Quick Start

```bash
# List SQL files in apply order (no database required)
sbt "run --sql-dir ./sql list"

# Validate without connecting to a database
sbt "run --sql-dir ./sql validate"

# Dry-run: output SQL that would be executed
sbt "run --sql-dir ./sql --dry-run apply"

# Check database connection
sbt "run --sql-dir ./sql check-connection"

# Apply migrations
sbt "run --sql-dir ./sql apply"

# Apply a Postgres-specific SQL tree
sbt "run --db-kind postgres --sql-dir ./sql/postgres apply"

# Fail when the live Postgres catalog drifts from the manifest and refresh the registry
sbt "run --db-kind postgres --sql-dir ./sql/postgres --customer fixture drift-check"
```

For Oracle, set the database type and provide the necessary credentials or environment variables:

```bash
SCHEMA_MIGRATOR_DB_KIND=oracle \
ORACLE_JDBC_URL=... \
ORACLE_USER=sys \
ORACLE_PASS_FILE=/run/secrets/oracle_password \
  sbt "run --sql-dir ./sql/oracle apply"
```

---

## Commands

| Command   | Description                                                               |
|-----------|---------------------------------------------------------------------------|
| `apply`   | Discover, validate, lock, and apply pending SQL objects in a guaranteed order. |
| `validate`| Parse SQL files and validate dependencies and rollback completeness without executing them. |
| `list`    | Print discovered SQL files and objects in the precise order they will be applied. |
| `status`  | Display the current schema configuration status.                           |
| `drift-check` | Compare the live Postgres catalog with the manifest, refresh `schema_control.object_customization_registry`, and exit non-zero when drift is detected. |

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
| `--db-test-allowed-hosts` | — | Comma-separated database hosts allowed for HTTP target connection tests and catalog reads. |
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
| `BEDROCK_DB_TEST_ALLOWED_HOSTS` | Comma-separated database hosts allowed for HTTP target connection tests and catalog reads. |
| `BEDROCK_API_BEARER_TOKEN` | Static bearer token accepted by the HTTP API and injected by nginx for the bundled UI. |
| `BEDROCK_ENCRYPT_KEY` | Base64 AES-256-GCM key used to encrypt persisted target passwords and API responses. |
| `BEDROCK_MONGO_URI` | MongoDB URI used by the HTTP API to persist targets, uploaded SQL files, patches, runs, and validations. |
| `BEDROCK_MONGO_DATABASE` | MongoDB database for persisted HTTP API state. |
| `BEDROCK_MONGO_TARGETS_COLLECTION` | MongoDB collection for persisted target records. |
| `BEDROCK_SQL_FILES_COLLECTION` | MongoDB collection for uploaded repository SQL files. Defaults to `sql_files`. |
| `BEDROCK_PATCHES_COLLECTION` | MongoDB collection for uploaded migration patches. Defaults to `patches`. |
| `BEDROCK_RUNS_COLLECTION` | MongoDB collection for migration run history and active-run guards. Defaults to `runs`. |
| `BEDROCK_VALIDATIONS_COLLECTION` | MongoDB collection for validation results. Defaults to `validations`. |

Oracle schema catalog and drift endpoints currently return `supported = false`; Oracle targets are limited to connection-level checks and JDBC migration execution until Oracle catalog introspection is added.

Postgres drift checks persist the latest object-level result per customer overlay in
`schema_control.object_customization_registry`. To install or query the report view:

```bash
psql "$DATABASE_URL" -f sql/registry/drift_report.sql
```

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
With Schema Migrator, streamline your migration processes and ensure a robust and efficient SQL environment.
