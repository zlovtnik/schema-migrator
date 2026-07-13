package com.sslproxy.schema.db

enum DialectStatementKey:
  case Prepare, LookupExisting, ApplyLog, UpdateStatus, UpdateStatusSkipped
  case RollbackStatus, FetchStatus, FetchReady, FetchRollbackTarget

trait DialectStatements:
  def prepareSql: String
  def lookupExistingSql: String
  def applyLogSql: String
  def updateStatusSql: String
  def updateStatusSkippedSql: String
  def rollbackStatusSql: String
  def fetchStatusSql: String
  def fetchReadySql: String
  def fetchRollbackTargetSql: String

  final def commonStatements: Map[DialectStatementKey, String] =
    Map(
      DialectStatementKey.Prepare -> prepareSql,
      DialectStatementKey.LookupExisting -> lookupExistingSql,
      DialectStatementKey.ApplyLog -> applyLogSql,
      DialectStatementKey.UpdateStatus -> updateStatusSql,
      DialectStatementKey.UpdateStatusSkipped -> updateStatusSkippedSql,
      DialectStatementKey.RollbackStatus -> rollbackStatusSql,
      DialectStatementKey.FetchStatus -> fetchStatusSql,
      DialectStatementKey.FetchReady -> fetchReadySql,
      DialectStatementKey.FetchRollbackTarget -> fetchRollbackTargetSql
    )
