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
      "seed_data",
      "functions",
      "packages",
      "contexts",
      "triggers",
      "ilm_policies",
      "security",
      "views",
      "scheduler"
    )

  def forDb(kind: DbKind): List[String] =
    kind match
      case DbKind.Postgres => postgres
      case DbKind.Oracle => oracle
