package com.sslproxy.schema.db

import cats.effect.Resource
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.engine.*

import java.nio.file.Path

trait DbProvider[F[_]]:
  def dialect: SqlDialect
  def session: Resource[F, DbSession[F]]

trait DbSession[F[_]]:
  def checkConnection: F[Unit]
  def bootstrap: F[Unit]
  def acquireLock: F[Unit]
  def releaseLock: F[Unit]
  def prepare(objects: List[SchemaObject]): F[List[PreparedObject]]
  def recordSkipped(prepared: PreparedObject): F[Unit]
  def executeObject(prepared: PreparedObject): F[Unit]
  def rollbackObject(sqlDir: Path, objectName: String): F[Unit]
  def fetchStatus: F[List[ObjectStatus]]
  def fetchReady: F[SchemaReadyStatus]
  def checkReady: F[Boolean]
