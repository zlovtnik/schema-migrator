package com.sslproxy.schema.db.tidb

import cats.effect.IO
import com.sslproxy.schema.db.LockManager
import com.sslproxy.schema.db.tidb.R2dbcSupport.*
import com.sslproxy.schema.error.MigratorError
import io.r2dbc.spi.Connection

final class TiDBLockManager(conn: Connection) extends LockManager[IO]:
  override def acquire: IO[Unit] =
    queryOne(conn, TiDBStatements.lockAcquireSql, Seq()) { row =>
      row.get(0, classOf[java.lang.Number])
    }.flatMap {
      case Some(n) if n.longValue() == 1L => IO.unit
      case _ => IO.raiseError(
        MigratorError.Apply("TiDB schema apply lock schema_migrate could not be acquired (timeout 10s)")
      )
    }

  override def release: IO[Unit] =
    queryOne(conn, TiDBStatements.lockReleaseSql, Seq()) { row =>
      row.get(0, classOf[java.lang.Number])
    }.flatMap {
      case Some(n) if n.longValue() == 1L => IO.unit
      case _ => IO.raiseError(
        MigratorError.LockNotHeld("TiDB schema apply lock schema_migrate was not held")
      )
    }
