package com.sslproxy.schema.db

import cats.effect.{IO, Resource}
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.engine.*

import java.nio.file.Path

trait DbProvider:
  def dialect: SqlDialect
  def session: Resource[IO, DbSession]

trait DbSession:
  def checkConnection: IO[Unit]
  def bootstrap: IO[Unit]
  def acquireLock: IO[Unit]
  def releaseLock: IO[Unit]
  def prepare(objects: List[SchemaObject]): IO[List[PreparedObject]]
  def recordSkipped(prepared: PreparedObject): IO[Unit]
  def executeObject(prepared: PreparedObject): IO[Unit]
  def rollbackObject(sqlDir: Path, objectName: String): IO[Unit]
  def fetchStatus: IO[List[ObjectStatus]]
  def fetchReady: IO[SchemaReadyStatus]
  def checkReady: IO[Boolean]

