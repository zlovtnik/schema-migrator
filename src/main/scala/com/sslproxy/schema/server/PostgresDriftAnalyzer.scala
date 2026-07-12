package com.sslproxy.schema.server

import com.sslproxy.schema.engine.SchemaObject
import com.sslproxy.schema.store.{DriftItem, SchemaCatalogObject}

private[schema] object PostgresDriftAnalyzer:
  type ObjectKey = PostgresDriftModel.ObjectKey
  val ObjectKey = PostgresDriftModel.ObjectKey
  type RoutineDefinition = PostgresDriftModel.RoutineDefinition
  val RoutineDefinition = PostgresDriftModel.RoutineDefinition
  type DdlDefinition = PostgresDriftModel.DdlDefinition
  val DdlDefinition = PostgresDriftModel.DdlDefinition
  type ExpectedObject = PostgresDriftModel.ExpectedObject
  val ExpectedObject = PostgresDriftModel.ExpectedObject
  type LiveObject = PostgresDriftModel.LiveObject
  val LiveObject = PostgresDriftModel.LiveObject
  type ControlRow = PostgresDriftModel.ControlRow
  val ControlRow = PostgresDriftModel.ControlRow
  type ControlObject = PostgresDriftModel.ControlObject
  val ControlObject = PostgresDriftModel.ControlObject
  type ExpectedSnapshot = PostgresDriftModel.ExpectedSnapshot
  val ExpectedSnapshot = PostgresDriftModel.ExpectedSnapshot
  type ControlSnapshot = PostgresDriftModel.ControlSnapshot
  val ControlSnapshot = PostgresDriftModel.ControlSnapshot

  def expectedFromManifest(item: SchemaObject): List[ExpectedObject] =
    PostgresDriftDiffEngine.expectedFromManifest(item)

  def controlSnapshot(rows: List[ControlRow]): ControlSnapshot =
    PostgresDriftDiffEngine.controlSnapshot(rows)

  def unavailableControlSnapshot(message: String): ControlSnapshot =
    PostgresDriftDiffEngine.unavailableControlSnapshot(message)

  def normalizeObjectKey(key: ObjectKey): ObjectKey =
    PostgresDriftDdlParser.normalizeObjectKey(key)

  def mergeCatalog(
    now: String,
    expected: List[ExpectedObject],
    actual: List[LiveObject],
    control: ControlSnapshot
  ): List[SchemaCatalogObject] =
    PostgresDriftDiffEngine.mergeCatalog(now, expected, actual, control)

  def driftItems(
    now: String,
    expected: List[ExpectedObject],
    actual: List[LiveObject],
    control: ControlSnapshot
  ): List[DriftItem] =
    PostgresDriftDiffEngine.driftItems(now, expected, actual, control)

  def routineDefinitions(sql: String): List[RoutineDefinition] =
    PostgresDriftDdlParser.routineDefinitions(sql)

  def catalogDefinitions(sql: String): List[DdlDefinition] =
    PostgresDriftDdlParser.catalogDefinitions(sql)

  def definitionHash(key: ObjectKey, value: String): String =
    PostgresDriftDdlParser.definitionHash(key, value)
