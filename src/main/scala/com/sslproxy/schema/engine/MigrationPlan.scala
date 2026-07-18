package com.sslproxy.schema.engine

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig}
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.discovery.{DiscoveryResult, DiscoveryService, SqlFile, SqlPathNormalizer}
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.validation.{ValidationReport, Validator}

final case class MigrationPlan(
  files: List[SqlFile],
  objects: List[SchemaObject],
  retiredObjects: List[SchemaObject],
  warnings: List[String],
  validation: ValidationReport
)

object MigrationPlan:
  def prepare(
    config: MigratorConfig,
    dialect: SqlDialect,
    discoveryService: DiscoveryService
  ): IO[MigrationPlan] =
    discoveryService
      .discover(config.sqlDir, config.dbKind, config.customer)
      .flatMap(fromDiscovery(config.dbKind, dialect, _))

  def prepareFiles(
    dbKind: DbKind,
    files: List[SqlFile],
    dialect: SqlDialect,
    discoveryService: DiscoveryService
  ): IO[MigrationPlan] =
    inspectFiles(dbKind, files, dialect, discoveryService).flatMap(requireValid)

  def inspectFiles(
    dbKind: DbKind,
    files: List[SqlFile],
    dialect: SqlDialect,
    discoveryService: DiscoveryService = DiscoveryService()
  ): IO[MigrationPlan] =
    val discovery = discoveryService.discoverFromFiles(files, dbKind)
    val validationFiles = files.flatMap { file =>
      SqlPathNormalizer.normalizeForDb(file, dbKind) match
        case Right(Some(normalized)) => Some(normalized)
        case Right(None) => None
        case Left(_) => Some(file)
    }
    inspectValidated(dbKind, dialect, discovery, validationFiles)

  def inspect(dbKind: DbKind, dialect: SqlDialect, discovery: DiscoveryResult): IO[MigrationPlan] =
    inspectValidated(dbKind, dialect, discovery, discovery.files)

  private def inspectValidated(
    dbKind: DbKind,
    dialect: SqlDialect,
    discovery: DiscoveryResult,
    validationFiles: List[SqlFile]
  ): IO[MigrationPlan] =
    Validator(dbKind).validate(validationFiles).flatMap { validation =>
      val checked = validation.copy(warnings = (discovery.warnings ++ validation.warnings).distinct)
      buildPlan(dialect, discovery, checked)
    }

  private def buildPlan(
    dialect: SqlDialect,
    discovery: DiscoveryResult,
    validation: ValidationReport
  ): IO[MigrationPlan] =
    val builder = ManifestBuilder(dialect)
    (builder.build(discovery.files), builder.build(discovery.retiredFiles)).tupled.attempt.map {
      case Left(error) =>
        val message = Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString)
        emptyPlan(discovery, validation.copy(errors = (validation.errors :+ message).distinct))
      case Right((objects, retiredObjects)) =>
        val graph = Graph.topologicalSort(objects)
        val graphErrors = validateGraph(objects) ++ graph.left.toOption.map(_.getMessage).toList
        val combined = validation.copy(errors = (validation.errors ++ graphErrors).distinct)
        MigrationPlan(
          discovery.files,
          graph.getOrElse(objects),
          retiredObjects,
          combined.warnings,
          combined
        )
    }

  private def emptyPlan(discovery: DiscoveryResult, validation: ValidationReport): MigrationPlan =
    MigrationPlan(discovery.files, Nil, Nil, validation.warnings, validation)

  private def fromDiscovery(
    dbKind: DbKind,
    dialect: SqlDialect,
    discovery: DiscoveryResult
  ): IO[MigrationPlan] =
    inspect(dbKind, dialect, discovery).flatMap(requireValid)

  private def requireValid(plan: MigrationPlan): IO[MigrationPlan] =
    if plan.validation.hasErrors then
      IO.raiseError(MigratorError.Validation(plan.validation.errors.mkString("; ")))
    else IO.pure(plan)

  private def validateGraph(objects: List[SchemaObject]): List[String] =
    val byName = objects.groupBy(_.objectName)
    val duplicates = byName.collect { case (name, matches) if matches.sizeIs > 1 =>
      s"duplicate object identity '$name' in ${matches.map(_.sourceFile).sorted.mkString(", ")}"
    }.toList.sorted
    val known = byName.keySet
    val missing = objects.flatMap { objectDef =>
      objectDef.dependsOn.filterNot(known).filterNot(isExternal).map(dependency =>
        s"${objectDef.sourceFile}: ${objectDef.objectName} depends on missing object '$dependency'"
      )
    }.distinct.sorted
    duplicates ++ missing

  private def isExternal(dependency: String): Boolean =
    dependency.startsWith("ext:") || dependency.startsWith("external:")
