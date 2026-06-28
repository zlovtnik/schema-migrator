package com.sslproxy.schema.engine

final case class SchemaObject(
  kind: String,
  objectName: String,
  sourceFile: String,
  dependsOn: List[String],
  rollbackFile: Option[String],
  transactional: Boolean,
  rawSql: String,
  canonicalSql: String,
  sha256: String,
  oracleHeaders: Map[String, String] = Map.empty
):
  def phase: Phase = Phase.forKind(kind)

final case class PreparedObject(
  objectDef: SchemaObject,
  oldSha256: Option[String],
  needsApply: Boolean
)

final case class ObjectStatus(
  kind: String,
  objectName: String,
  sourceFile: String,
  applyStatus: String,
  contentSha256: String,
  appliedAt: Option[String],
  lastError: Option[String]
)

final case class SchemaReadyStatus(
  totalCount: Long,
  pendingCount: Long,
  failedCount: Long,
  appliedCount: Long,
  ready: Boolean,
  failedObjects: List[String],
  lastUpdatedAt: Option[String],
  lastAppliedAt: Option[String]
)

final case class RollbackTarget(
  kind: String,
  objectName: String,
  sourceFile: String,
  contentSha256: String,
  rollbackFile: String
)

final case class ApplyReport(appliedFiles: Int = 0, skippedFiles: Int = 0, failedFiles: Int = 0):
  def combine(other: ApplyReport): ApplyReport =
    ApplyReport(
      appliedFiles + other.appliedFiles,
      skippedFiles + other.skippedFiles,
      failedFiles + other.failedFiles
    )
