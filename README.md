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

With Schema Migrator, streamline your migration processes and ensure a robust and efficient SQL environment.
