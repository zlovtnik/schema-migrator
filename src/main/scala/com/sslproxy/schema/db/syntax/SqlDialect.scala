package com.sslproxy.schema.db.syntax

import com.sslproxy.schema.config.DbKind

enum SqlDialect:
  case Postgres, Oracle

object SqlDialect:
  def forDbKind(dbKind: DbKind): SqlDialect =
    dbKind match
      case DbKind.Postgres => Postgres
      case DbKind.Oracle => Oracle
