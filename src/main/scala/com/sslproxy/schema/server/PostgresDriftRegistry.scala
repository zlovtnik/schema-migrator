package com.sslproxy.schema.server

import cats.effect.IO
import com.sslproxy.schema.db.{JdbcConnectionConfig, JdbcSupport}
import com.sslproxy.schema.db.postgres.PostgresStatements
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.store.{DriftItem, SchemaCatalogObject}

import java.sql.{Connection, PreparedStatement, Timestamp}
import java.time.Instant
import scala.util.control.NonFatal

private[schema] object PostgresDriftRegistry:
  import PostgresDriftAnalyzer.*

  def record(
    config: JdbcConnectionConfig,
    customer: String,
    catalog: List[SchemaCatalogObject],
    drift: List[DriftItem]
  ): IO[Unit] =
    JdbcSupport.connection(config).use { connection =>
      IO.blocking(record(connection, customer, catalog, drift))
    }

  private def record(
    connection: Connection,
    customer: String,
    catalog: List[SchemaCatalogObject],
    drift: List[DriftItem]
  ): Unit =
    val oldAutoCommit = connection.getAutoCommit
    connection.setAutoCommit(false)
    try
      JdbcSupport.executeStatement(connection, PostgresStatements.ensureSchemaControlSchemaSql)
      JdbcSupport.executeStatement(connection, PostgresStatements.customizationRegistrySql)
      deleteCustomerRows(connection, customer)
      insertRows(connection, registryRows(customer, catalog, drift))
      connection.commit()
    catch
      case NonFatal(error) =>
        connection.rollback()
        throw MigratorError.Apply(s"failed to record Postgres drift registry for customer '$customer'", error)
    finally connection.setAutoCommit(oldAutoCommit)

  private def deleteCustomerRows(connection: Connection, customer: String): Unit =
    JdbcSupport.executePrepared(
      connection,
      PostgresStatements.deleteCustomerRowsSql
    )(_.setString(1, customer))

  private def insertRows(connection: Connection, rows: List[RegistryRow]): Unit =
    if rows.nonEmpty then
      val statement = connection.prepareStatement(PostgresStatements.insertCustomizationRegistrySql)
      try
        rows.foreach(row => bindRow(statement, row))
        statement.executeBatch()
        ()
      finally statement.close()

  private def bindRow(statement: PreparedStatement, row: RegistryRow): Unit =
    statement.setString(1, row.customer)
    statement.setString(2, row.objectSchema)
    statement.setString(3, row.objectName)
    statement.setString(4, row.objectType)
    setNullable(statement, 5, row.sourceFile)
    setNullable(statement, 6, row.coreHash)
    setNullable(statement, 7, row.liveHash)
    statement.setString(8, row.status)
    setNullable(statement, 9, row.driftType)
    setNullable(statement, 10, row.applyStatus)
    setNullable(statement, 11, row.expectedDdl)
    setNullable(statement, 12, row.actualDdl)
    statement.setTimestamp(13, Timestamp.from(Instant.parse(row.lastChecked)))
    statement.addBatch()

  private def setNullable(statement: PreparedStatement, index: Int, value: Option[String]): Unit =
    value.fold(statement.setNull(index, java.sql.Types.VARCHAR))(statement.setString(index, _))

  private[schema] def registryRows(
    customer: String,
    catalog: List[SchemaCatalogObject],
    drift: List[DriftItem]
  ): List[RegistryRow] =
    val driftByKey =
      drift
        .groupBy(item => (item.schema, item.object_type, item.name))
        .view
        .mapValues(_.head)
        .toMap

    catalog.map { item =>
      val key = ObjectKey(item.schema, item.name, item.object_type)
      val driftItem = driftByKey.get((item.schema, item.object_type, item.name))
      RegistryRow(
        customer = customer,
        objectSchema = item.schema,
        objectName = item.name,
        objectType = item.object_type,
        sourceFile = item.source_file,
        coreHash = item.expected_ddl.map(definitionHash(key, _)).orElse(item.checksum),
        liveHash = item.actual_ddl.map(definitionHash(key, _)),
        status = item.status,
        driftType = driftItem.map(_.drift_type),
        applyStatus = item.apply_status,
        expectedDdl = item.expected_ddl,
        actualDdl = item.actual_ddl,
        lastChecked = item.last_checked
      )
    }

  private[schema] final case class RegistryRow(
    customer: String,
    objectSchema: String,
    objectName: String,
    objectType: String,
    sourceFile: Option[String],
    coreHash: Option[String],
    liveHash: Option[String],
    status: String,
    driftType: Option[String],
    applyStatus: Option[String],
    expectedDdl: Option[String],
    actualDdl: Option[String],
    lastChecked: String
  )
