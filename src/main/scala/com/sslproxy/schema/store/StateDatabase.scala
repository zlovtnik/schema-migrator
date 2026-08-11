package com.sslproxy.schema.store

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.config.StateStoreConfig
import com.zaxxer.hikari.HikariConfig
import doobie.*
import doobie.hikari.HikariTransactor
import doobie.implicits.*

import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.*

final case class StateDatabase(transactor: Transactor[IO]):
  def transact[A](action: ConnectionIO[A]): IO[A] =
    TiDBTransactionRetry.run(action.transact(transactor))

object StateDatabase:
  private val Driver = "com.mysql.cj.jdbc.Driver"

  def resource(config: StateStoreConfig): Resource[IO, StateDatabase] =
    for
      _ <- Resource.eval(IO.fromEither(config.validate.leftMap(IllegalArgumentException(_))))
      contract <- Resource.eval(StateSchemaContract.load)
      hikariConfig <- Resource.eval(IO.delay(poolConfig(config)))
      xa <- HikariTransactor.fromHikariConfig[IO](hikariConfig)
      database = StateDatabase(xa)
      _ <- Resource.eval(database.verifyRuntime(contract))
    yield database

  private def poolConfig(config: StateStoreConfig): HikariConfig =
    val value = HikariConfig()
    value.setDriverClassName(Driver)
    value.setJdbcUrl(config.url.trim)
    value.setUsername(config.user.trim)
    value.setPassword(config.password)
    value.setMaximumPoolSize(config.poolSize)
    value.setMinimumIdle(1)
    value.setPoolName("schema-migrator-tidb-state")
    value.setConnectionInitSql("SET time_zone = '+00:00'")
    value.addDataSourceProperty("connectionTimeZone", "UTC")
    value.addDataSourceProperty("forceConnectionTimeZoneToSession", "true")
    value.addDataSourceProperty("preserveInstants", "true")
    value.addDataSourceProperty("characterEncoding", "UTF-8")
    value

  extension (database: StateDatabase)
    private def verifyRuntime(contract: StateSchemaContract): IO[Unit] =
      database
        .transact(runtimeVerification(contract))
        .handleErrorWith(error => StateSchemaVerificationFailure.topLevel(error).raiseError[IO, Unit])

  private def runtimeVerification(contract: StateSchemaContract): ConnectionIO[Unit] =
    for
      version <- checked(StateSchemaVerificationFailure.Database)(sql"select version()".query[String].unique)
      _ <- Either
        .cond(isSupportedTiDB(version), (), "server must be TiDB v8.5 or newer")
        .leftMap(detail => StateSchemaVerificationFailure(StateSchemaVerificationFailure.Database, detail))
        .liftTo[ConnectionIO]
      database <- checked(StateSchemaVerificationFailure.Database)(sql"select database()".query[Option[String]].unique)
      _ <- Either
        .cond(database.contains("schema_migrator"), (), "selected database must be schema_migrator")
        .leftMap(detail => StateSchemaVerificationFailure(StateSchemaVerificationFailure.Database, detail))
        .liftTo[ConnectionIO]
      timeZone <- checked(StateSchemaVerificationFailure.SessionTimeZone)(sql"select @@session.time_zone".query[String].unique)
      _ <- Either
        .cond(Set("+00:00", "UTC").contains(timeZone), (), "session time zone must be UTC")
        .leftMap(detail => StateSchemaVerificationFailure(StateSchemaVerificationFailure.SessionTimeZone, detail))
        .liftTo[ConnectionIO]
      ledger <- checked(StateSchemaVerificationFailure.LedgerVersionChecksum)(
        sql"select checksum from state_schema_migrations where version = ${contract.version}".query[String].option
      )
      _ <- StateSchemaVerificationFailure
        .ledgerFailure(contract, ledger)
        .fold(().pure[ConnectionIO])(_.raiseError[ConnectionIO, Unit])
      readiness <- checked(StateSchemaVerificationFailure.Readiness)(sql"""
        select required_version, applied_version, required_checksum, applied_checksum, ready
        from schema_readiness
        where domain = 'schema_migrator'
      """.query[(String, String, String, String, Boolean)].option)
      _ <- StateSchemaVerificationFailure
        .readinessFailure(contract, readiness)
        .fold(().pure[ConnectionIO])(_.raiseError[ConnectionIO, Unit])
    yield ()

  private def checked[A](category: String)(action: ConnectionIO[A]): ConnectionIO[A] =
    action.handleErrorWith {
      case failure: StateSchemaVerificationFailure => failure.raiseError[ConnectionIO, A]
      case error =>
        StateSchemaVerificationFailure(category, "verification query failed", error).raiseError[ConnectionIO, A]
    }

  private[store] def isSupportedTiDB(value: String): Boolean =
    val Version = raw"(?i).*TiDB-v(\d+)\.(\d+)(?:\.\d+)?.*".r
    value match
      case Version(major, minor) =>
        val parsed = major.toInt -> minor.toInt
        parsed._1 > 8 || (parsed._1 == 8 && parsed._2 >= 5)
      case _ => false

private[store] final case class StateSchemaContract(version: String, checksum: String)

private[store] final class StateSchemaVerificationFailure(
  val category: String,
  val detail: String,
  cause: Throwable = null
) extends IllegalStateException(s"$category: $detail", cause)

private[store] object StateSchemaVerificationFailure:
  val Database = "database"
  val SessionTimeZone = "session-timezone"
  val LedgerVersionChecksum = "ledger-version-checksum"
  val Readiness = "readiness"

  def apply(category: String, detail: String, cause: Throwable = null): StateSchemaVerificationFailure =
    new StateSchemaVerificationFailure(category, detail, cause)

  def ledgerFailure(
    contract: StateSchemaContract,
    ledger: Option[String]
  ): Option[StateSchemaVerificationFailure] =
    ledger match
      case Some(checksum) if checksum.equalsIgnoreCase(contract.checksum) => None
      case Some(checksum) =>
        Some(
          StateSchemaVerificationFailure(
            LedgerVersionChecksum,
            s"checksum mismatch for version ${contract.version} (expected=${contract.checksum}, found=$checksum)"
          )
        )
      case None => Some(StateSchemaVerificationFailure(LedgerVersionChecksum, s"missing version ${contract.version}"))

  def readinessFailure(
    contract: StateSchemaContract,
    readiness: Option[(String, String, String, String, Boolean)]
  ): Option[StateSchemaVerificationFailure] =
    readiness match
      case Some((requiredVersion, appliedVersion, requiredChecksum, appliedChecksum, true))
          if requiredVersion == contract.version &&
            appliedVersion == contract.version &&
            requiredChecksum.equalsIgnoreCase(contract.checksum) &&
            appliedChecksum.equalsIgnoreCase(contract.checksum) => None
      case Some((requiredVersion, appliedVersion, requiredChecksum, appliedChecksum, ready)) =>
        Some(
          StateSchemaVerificationFailure(
            Readiness,
            s"mismatch (required_version=$requiredVersion, applied_version=$appliedVersion, " +
              s"required_checksum=$requiredChecksum, applied_checksum=$appliedChecksum, ready=$ready)"
          )
        )
      case None => Some(StateSchemaVerificationFailure(Readiness, "row is missing"))

  def topLevel(error: Throwable): IllegalStateException =
    error match
      case failure: StateSchemaVerificationFailure =>
        IllegalStateException(
          s"TiDB state schema verification failed [${failure.category}]: ${failure.detail}; " +
            "apply sql/tidb/schema_migrator with the provisioning schema job before starting the runtime",
          failure
        )
      case other =>
        IllegalStateException(
          "TiDB state schema verification failed [database]: verification query failed; " +
            "apply sql/tidb/schema_migrator with the provisioning schema job before starting the runtime",
          other
        )

private[store] object StateSchemaContract:
  private val ResourceName = "state-migrations/manifest.properties"
  private val Sha256 = "[0-9a-f]{64}".r

  def load: IO[StateSchemaContract] =
    Resource
      .fromAutoCloseable(
        IO.blocking(
          Option(Thread.currentThread().getContextClassLoader.getResourceAsStream(ResourceName))
            .getOrElse(throw IllegalStateException(s"missing state schema contract resource $ResourceName"))
        )
      )
      .use { stream =>
        IO.blocking {
          val properties = Properties()
          properties.load(stream)
          val version = Option(properties.getProperty("version")).map(_.trim).filter(_.nonEmpty)
            .getOrElse(throw IllegalStateException("state schema contract version is missing"))
          val checksum = Option(properties.getProperty("checksum")).map(_.trim.toLowerCase)
            .filter(value => Sha256.matches(value))
            .getOrElse(throw IllegalStateException("state schema contract checksum must be lowercase SHA-256"))
          StateSchemaContract(version, checksum)
        }
      }

private[store] object TiDBTransactionRetry:
  private val MaxAttempts = 5
  private val MaxElapsed = 2.seconds
  private val InitialDelay = 20.millis
  private val MaxDelay = 320.millis
  private val RetryableVendorCodes = Set(1205, 1213, 8028, 9007)

  def run[A](operation: IO[A]): IO[A] =
    IO.monotonic.flatMap(started => loop(operation, started, attempt = 1, InitialDelay))

  private def loop[A](operation: IO[A], started: FiniteDuration, attempt: Int, delay: FiniteDuration): IO[A] =
    operation.handleErrorWith { error =>
      IO.monotonic.flatMap { now =>
        if attempt >= MaxAttempts || now - started >= MaxElapsed || !isRetryable(error) then IO.raiseError(error)
        else
          IO.delay(ThreadLocalRandom.current().nextDouble()).flatMap { random =>
            val jittered = (delay.toNanos.toDouble * (0.75d + random * 0.5d)).toLong.nanos
            IO.sleep(jittered) *> loop(operation, started, attempt + 1, (delay * 2).min(MaxDelay))
          }
      }
    }

  private[store] def isRetryable(error: Throwable): Boolean =
    findSqlException(error).exists { sql =>
      Option(sql.getSQLState).contains("40001") || RetryableVendorCodes.contains(sql.getErrorCode)
    }

  private def findSqlException(error: Throwable): Option[SQLException] =
    error match
      case sql: SQLException => Some(sql)
      case other => Option(other.getCause).flatMap(findSqlException)
