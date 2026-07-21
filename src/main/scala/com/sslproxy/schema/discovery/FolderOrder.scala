package com.sslproxy.schema.discovery

import com.sslproxy.schema.config.DbKind

object FolderOrder:
  val postgres: List[String] =
    List(
      "extensions",
      "schemas",
      "types",
      "tables",
      "indexes",
      "functions",
      "views",
      "cron",
      "materialized_views"
    )

  val oracle: List[String] =
    List(
      "session",
      "types",
      "tables",
      "indexes",
      "seed_data",
      "functions",
      "procedures",
      "packages",
      "contexts",
      "triggers",
      "ilm_policies",
      "security",
      "views",
      "materialized_views",
      "cron",
      "policies",
      "scheduler"
    )

  val tidb: List[String] =
    List(
      "schemas",
      "tables",
      "indexes",
      "functions",
      "views",
      "cron",
      "materialized_views"
    )

  def forDb(kind: DbKind): List[String] =
    kind match
      case DbKind.Postgres => postgres
      case DbKind.Oracle => oracle
      case DbKind.TiDB => tidb
