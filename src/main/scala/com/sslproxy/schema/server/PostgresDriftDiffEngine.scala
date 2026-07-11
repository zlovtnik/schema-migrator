package com.sslproxy.schema.server

import com.sslproxy.schema.engine.SchemaObject
import com.sslproxy.schema.store.{DriftItem, SchemaCatalogObject, SchemaControlObject, SchemaControlSummary}

private[schema] object PostgresDriftDiffEngine:
  import PostgresDriftModel.*

  def expectedFromManifest(item: SchemaObject): List[ExpectedObject] =
    PostgresDriftDdlParser
      .catalogDefinitions(item.rawSql)
      .map(definition =>
        ExpectedObject(
          key = definition.key,
          sourceFile = item.sourceFile,
          checksum = item.sha256,
          expectedDdl = Some(definition.ddl),
          applyStatus = None
        )
      )

  def controlSnapshot(rows: List[ControlRow]): ControlSnapshot =
    ControlSnapshot(
      objects = rows.flatMap(controlObjectsForRow),
      summary = Some(controlSummary(rows)),
      warnings = Nil,
      rows = rows.map(controlObject)
    )

  def unavailableControlSnapshot(message: String): ControlSnapshot =
    ControlSnapshot(Nil, None, List(message))

  def normalizeObjectKey(key: ObjectKey): ObjectKey =
    PostgresDriftDdlParser.normalizeObjectKey(key)

  def mergeCatalog(
    now: String,
    expected: List[ExpectedObject],
    actual: List[LiveObject],
    control: ControlSnapshot
  ): List[SchemaCatalogObject] =
    val state = PreparedState(expected, control.objects)
    val actualByKey = liveObjectsByKey(actual)
    val keys = (state.trackedByKey.keySet ++ actualByKey.keySet).toList.sorted
    keys.map { key =>
      val expectedItem = state.trackedByKey.get(key)
      val actualItem = actualByKey.get(key)
      val controlItem = state.controlForKey(key)
      val applyStatus = controlItem.map(_.applyStatus).orElse(expectedItem.flatMap(_.applyStatus))
      val status =
        applyStatus match
          case Some("pending" | "failed") => "pending_migration"
          case _
              if PostgresDriftDdlParser.definitionChanged(
                key,
                expectedItem.flatMap(_.expectedDdl).orElse(controlItem.flatMap(_.expectedDdl)),
                actualItem.flatMap(_.actualDdl)
              ) =>
            "drift_detected"
          case _ if expectedItem.nonEmpty && actualItem.nonEmpty => "in_sync"
          case _ if expectedItem.nonEmpty => "drift_detected"
          case _ => "unknown"
      SchemaCatalogObject(
        schema = key.schema,
        name = key.name,
        object_type = key.objectType,
        status = status,
        source_file = controlItem.map(_.sourceFile).orElse(expectedItem.map(_.sourceFile)),
        checksum = controlItem.map(_.checksum).orElse(expectedItem.map(_.checksum)),
        apply_status = applyStatus,
        actual_ddl = actualItem.flatMap(_.actualDdl),
        expected_ddl = expectedItem.flatMap(_.expectedDdl).orElse(controlItem.flatMap(_.expectedDdl)),
        last_checked = now
      )
    }

  def driftItems(
    now: String,
    expected: List[ExpectedObject],
    actual: List[LiveObject],
    control: ControlSnapshot
  ): List[DriftItem] =
    val state = PreparedState(expected, control.objects)
    val actualByKey = liveObjectsByKey(actual)
    val pendingOrFailedKeys = state.pendingOrFailedKeys

    val missingActual =
      state.trackedByKey.values.toList
        .filterNot(item => actualByKey.contains(item.key))
        .filterNot(item => pendingOrFailedKeys.contains(item.key))
        .map(item =>
          val controlItem = state.controlForKey(item.key)
          DriftItem(
            schema = item.key.schema,
            name = item.key.name,
            object_type = item.key.objectType,
            drift_type = "missing_actual",
            expected = item.sourceFile,
            actual = "not present in live Postgres catalog",
            source_file = Some(item.sourceFile),
            checksum = Some(item.checksum),
            apply_status = controlItem.map(_.applyStatus).orElse(item.applyStatus),
            detected_at = now
          )
        )

    val untrackedActual =
      actualByKey.values.toList
        .filterNot(item => state.trackedByKey.contains(item.key))
        .filterNot(item => item.key.schema == "schema_control")
        .filterNot(item => ignoredUntrackedActual(item.key))
        .map(item =>
          DriftItem(
            schema = item.key.schema,
            name = item.key.name,
            object_type = item.key.objectType,
            drift_type = "untracked_actual",
            expected = "not defined in SQL manifest or schema_control",
            actual = item.actualDdl.getOrElse("present in live Postgres catalog"),
            source_file = None,
            checksum = None,
            apply_status = None,
            detected_at = now
          )
        )

    val pendingOrFailed =
      state.controlByKey.values.toList.collect {
        case value if value.applyStatus == "pending" || value.applyStatus == "failed" =>
          DriftItem(
            schema = value.key.schema,
            name = value.key.name,
            object_type = value.key.objectType,
            drift_type = "pending_or_failed_control",
            expected = value.sourceFile,
            actual = s"schema_control apply_status=${value.applyStatus}",
            source_file = Some(value.sourceFile),
            checksum = Some(value.checksum),
            apply_status = Some(value.applyStatus),
            detected_at = now
          )
      }

    val changedDefinitions =
      state.trackedByKey.values.toList.flatMap { expectedItem =>
        val controlItem = state.controlForKey(expectedItem.key)
        if pendingOrFailedKeys.contains(expectedItem.key) then None
        else
          actualByKey.get(expectedItem.key).flatMap { actualItem =>
            actualItem.actualDdl
              .filter(actualDdl =>
                PostgresDriftDdlParser.definitionChanged(expectedItem.key, expectedItem.expectedDdl, Some(actualDdl))
              )
              .map { actualDdl =>
                DriftItem(
                  schema = expectedItem.key.schema,
                  name = expectedItem.key.name,
                  object_type = expectedItem.key.objectType,
                  drift_type = "definition_changed",
                  expected = expectedItem.expectedDdl.getOrElse(expectedItem.sourceFile),
                  actual = actualDdl,
                  source_file = Some(expectedItem.sourceFile),
                  checksum = Some(expectedItem.checksum),
                  apply_status = controlItem.map(_.applyStatus).orElse(expectedItem.applyStatus),
                  detected_at = now
                )
              }
          }
      }

    (missingActual ++ untrackedActual ++ changedDefinitions ++ pendingOrFailed).sortBy(item =>
      (item.schema, item.object_type, item.name, item.drift_type)
    )

  private def liveObjectsByKey(actual: List[LiveObject]): Map[ObjectKey, LiveObject] =
    actual
      .map(item =>
        val normalizedKey = normalizeObjectKey(item.key)
        normalizedKey -> item.copy(key = normalizedKey)
      )
      .toMap

  private final case class PreparedState(
    trackedByKey: Map[ObjectKey, ExpectedObject],
    controlByKey: Map[ObjectKey, ControlObject],
    expectedControlByKey: Map[ObjectKey, ControlObject]
  ):
    def controlForKey(key: ObjectKey): Option[ControlObject] =
      controlByKey.get(key).orElse(expectedControlByKey.get(key))

    def pendingOrFailedKeys: Set[ObjectKey] =
      (controlByKey.toList ++ expectedControlByKey.toList).collect {
        case (key, value) if value.applyStatus == "pending" || value.applyStatus == "failed" => key
      }.toSet

  private object PreparedState:
    def apply(expected: List[ExpectedObject], control: List[ControlObject]): PreparedState =
      val normalizedControl = control.map(item => item.copy(key = normalizeObjectKey(item.key)))
      val normalizedExpected = expected.map(item => item.copy(key = normalizeObjectKey(item.key)))
      val controlByKey = normalizedControl.map(item => item.key -> item).toMap
      val controlBySource = normalizedControl
        .groupBy(_.sourceFile)
        .collect { case (sourceFile, item :: Nil) => sourceFile -> item }
      val expectedControlByKey = normalizedExpected.flatMap { item =>
        controlByKey.get(item.key).orElse(controlBySource.get(item.sourceFile)).map(item.key -> _)
      }.toMap
      val expectedWithControl = normalizedExpected.map { item =>
        val controlItem = expectedControlByKey.get(item.key)
        item.key -> item.copy(applyStatus = controlItem.map(_.applyStatus).orElse(item.applyStatus))
      }.toMap
      val controlTracked = controlByKey.map { case (key, item) =>
        key -> ExpectedObject(
          key = key,
          sourceFile = item.sourceFile,
          checksum = item.checksum,
          expectedDdl = item.expectedDdl,
          applyStatus = Some(item.applyStatus)
        )
      }
      PreparedState(
        trackedByKey = controlTracked ++ expectedWithControl,
        controlByKey = controlByKey,
        expectedControlByKey = expectedControlByKey
      )

  private def controlObjectsForRow(row: ControlRow): List[ControlObject] =
    row.expectedDdl.toList
      .flatMap(PostgresDriftDdlParser.catalogDefinitions)
      .map(definition =>
        ControlObject(
          key = definition.key,
          applyStatus = row.applyStatus,
          sourceFile = row.sourceFile,
          checksum = row.checksum,
          expectedDdl = Some(definition.ddl),
          objectName = row.objectName
        )
      )

  private def controlSummary(rows: List[ControlRow]): SchemaControlSummary =
    val applied = rows.count(_.applyStatus == "applied").toLong
    val skipped = rows.count(_.applyStatus == "skipped").toLong
    val pending = rows.count(_.applyStatus == "pending").toLong
    val failed = rows.count(_.applyStatus == "failed").toLong
    SchemaControlSummary(
      total_count = rows.length.toLong,
      applied_count = applied,
      skipped_count = skipped,
      pending_count = pending,
      failed_count = failed,
      ready = rows.nonEmpty && pending == 0 && failed == 0,
      failed_objects = rows.filter(_.applyStatus == "failed").map(row => s"${row.kind}:${row.objectName}").sorted,
      last_applied_at = rows.flatMap(_.appliedAt).maxOption,
      last_updated_at = rows.flatMap(_.updatedAt).maxOption
    )

  private def controlObject(row: ControlRow): SchemaControlObject =
    SchemaControlObject(
      kind = row.kind,
      object_name = row.objectName,
      source_file = row.sourceFile,
      apply_status = row.applyStatus,
      checksum = row.checksum,
      applied_at = row.appliedAt,
      updated_at = row.updatedAt
    )

  private def ignoredUntrackedActual(key: ObjectKey): Boolean =
    (key.objectType == "schema" && key.schema == "public" && key.name == "public") ||
      (key.objectType == "extension" && key.schema == "public" && key.name == "pg_stat_statements") ||
      (key.objectType == "trigger" && key.schema == "cron" && key.name.startsWith("job.cron_"))
