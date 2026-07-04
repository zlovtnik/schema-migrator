package com.sslproxy.schema.server

import cats.effect.IO
import com.sslproxy.schema.db.{JdbcConnectionConfig, JdbcSupport}
import com.sslproxy.schema.db.postgres.PostgresStatements
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
      JdbcSupport.executeStatement(connection, "create schema if not exists schema_control")
      JdbcSupport.executeStatement(connection, PostgresStatements.customizationRegistrySql)
      deleteCustomerRows(connection, customer)
      insertRows(connection, rows(customer, catalog, drift))
      connection.commit()
    catch
      case NonFatal(error) =>
        connection.rollback()
        throw error
    finally connection.setAutoCommit(oldAutoCommit)

  private def deleteCustomerRows(connection: Connection, customer: String): Unit =
    JdbcSupport.executePrepared(
      connection,
      "delete from schema_control.object_customization_registry where customer = ?"
    )(_.setString(1, customer))

  private def insertRows(connection: Connection, rows: List[RegistryRow]): Unit =
    if rows.nonEmpty then
      val statement = connection.prepareStatement(insertSql)
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

  private def rows(
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

  private final case class RegistryRow(
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

  private val insertSql: String =
    """
    insert into schema_control.object_customization_registry (
      customer,
      object_schema,
      object_name,
      object_type,
      source_file,
      core_hash,
      live_hash,
      status,
      drift_type,
      apply_status,
      expected_ddl,
      actual_ddl,
      last_checked
    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    on conflict (customer, object_schema, object_type, object_name) do update set
      source_file = excluded.source_file,
      core_hash = excluded.core_hash,
      live_hash = excluded.live_hash,
      status = excluded.status,
      drift_type = excluded.drift_type,
      apply_status = excluded.apply_status,
      expected_ddl = excluded.expected_ddl,
      actual_ddl = excluded.actual_ddl,
      last_checked = excluded.last_checked
    """
