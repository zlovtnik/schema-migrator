package com.sslproxy.schema.db.tidb

import cats.effect.IO
import io.r2dbc.spi.{Connection, Row, Statement}
import org.reactivestreams.Publisher
import reactor.core.publisher.{Flux, Mono}

import scala.jdk.CollectionConverters.*

object R2dbcSupport:
  def publisherToIO[A](pub: Publisher[A]): IO[A] =
    IO.fromCompletableFuture(IO(Mono.from(pub).toFuture))

  def publisherToList[A](pub: Publisher[A]): IO[List[A]] =
    IO.fromCompletableFuture(
      IO(Flux.from(pub).collectList().toFuture.thenApply(_.asScala.toList))
    )

  def execUpdate(conn: Connection, sql: String, params: Seq[Any]): IO[Int] =
    val stmt = conn.createStatement(sql)
    bindParams(stmt, params)
    publisherToIO(stmt.execute())
      .flatMap(result => publisherToIO(result.getRowsUpdated()).map(v => v.intValue()))

  def queryOne[A](conn: Connection, sql: String, params: Seq[Any])(f: Row => A): IO[Option[A]] =
    val stmt = conn.createStatement(sql)
    bindParams(stmt, params)
    publisherToList(
      Flux.from(stmt.execute())
        .flatMap(result => result.map((row, _) => f(row)))
    ).map(_.headOption)

  def queryList[A](conn: Connection, sql: String, params: Seq[Any])(f: Row => A): IO[List[A]] =
    val stmt = conn.createStatement(sql)
    bindParams(stmt, params)
    publisherToList(
      Flux.from(stmt.execute())
        .flatMap(result => result.map((row, _) => f(row)))
    )

  def bindParams(stmt: Statement, params: Seq[Any]): Statement =
    params.zipWithIndex.foreach {
      case (v: String, i) => stmt.bind(i, v)
      case (v: Int, i) => stmt.bind(i, v)
      case (v: Long, i) => stmt.bind(i, v)
      case (v: java.lang.Integer, i) => stmt.bind(i, v)
      case (v: java.lang.Long, i) => stmt.bind(i, v)
      case (null, i) => stmt.bindNull(i, classOf[Any])
      case (Some(v: String), i) => stmt.bind(i, v)
      case (None, i) => stmt.bindNull(i, classOf[Any])
      case (v, i) => stmt.bind(i, v)
    }
    stmt

  def readString(row: io.r2dbc.spi.Row, col: String): String =
    Option(row.get(col, classOf[String])).orNull

  def readStringOpt(row: io.r2dbc.spi.Row, col: String): Option[String] =
    Option(row.get(col, classOf[String])).flatMap(v => Option(v))

  def readLong(row: io.r2dbc.spi.Row, col: String): Long =
    Option(row.get(col, classOf[java.lang.Number])).map(_.longValue()).getOrElse(0L)
