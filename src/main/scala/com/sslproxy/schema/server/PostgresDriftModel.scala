package com.sslproxy.schema.server

import com.sslproxy.schema.store.{SchemaCatalogObject, SchemaControlObject, SchemaControlSummary}

private[schema] object PostgresDriftModel:
  final case class ObjectKey(schema: String, name: String, objectType: String) extends Ordered[ObjectKey]:
    override def compare(that: ObjectKey): Int =
      Ordering
        .Tuple3[String, String, String]
        .compare((schema, objectType, name), (that.schema, that.objectType, that.name))

  final case class RoutineDefinition(key: ObjectKey, ddl: String)
  final case class DdlDefinition(key: ObjectKey, ddl: String)

  final case class ExpectedObject(
    key: ObjectKey,
    sourceFile: String,
    checksum: String,
    expectedDdl: Option[String],
    applyStatus: Option[String]
  ):
    def toCatalog(now: String, actual: Option[LiveObject], status: String): SchemaCatalogObject =
      SchemaCatalogObject(
        schema = key.schema,
        name = key.name,
        object_type = key.objectType,
        status = status,
        source_file = Some(sourceFile),
        checksum = Some(checksum),
        apply_status = applyStatus,
        actual_ddl = actual.flatMap(_.actualDdl),
        expected_ddl = expectedDdl,
        last_checked = now
      )

  final case class LiveObject(key: ObjectKey, actualDdl: Option[String])

  final case class ControlRow(
    kind: String,
    objectName: String,
    sourceFile: String,
    applyStatus: String,
    checksum: String,
    expectedDdl: Option[String],
    appliedAt: Option[String],
    updatedAt: Option[String]
  )

  final case class ControlObject(
    key: ObjectKey,
    applyStatus: String,
    sourceFile: String,
    checksum: String,
    expectedDdl: Option[String],
    objectName: String
  )

  final case class ExpectedSnapshot(objects: List[ExpectedObject], warnings: List[String])
  final case class ControlSnapshot(
    objects: List[ControlObject],
    summary: Option[SchemaControlSummary],
    warnings: List[String],
    rows: List[SchemaControlObject] = Nil
  )
