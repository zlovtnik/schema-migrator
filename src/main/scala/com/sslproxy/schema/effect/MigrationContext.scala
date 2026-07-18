package com.sslproxy.schema.effect

import com.sslproxy.schema.config.{DbKind, MigratorConfig}

/** Metadata that describes a migration run. */
final case class MigrationRunContext(
  runId: Option[String],
  targetId: Option[String],
  dbKind: DbKind,
  customer: Option[String],
  dryRun: Boolean
)

object MigrationRunContext:
  def fromConfig(config: MigratorConfig): MigrationRunContext =
    MigrationRunContext(
      runId = None,
      targetId = None,
      dbKind = config.dbKind,
      customer = config.customer,
      dryRun = config.dryRun
    )
