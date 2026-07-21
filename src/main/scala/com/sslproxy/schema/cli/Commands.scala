package com.sslproxy.schema.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig}
import com.sslproxy.schema.db.DbProvider
import com.sslproxy.schema.db.oracle.OracleProvider
import com.sslproxy.schema.db.postgres.PostgresProvider
import com.sslproxy.schema.db.tidb.TiDBProvider
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.discovery.{BaselineGenerator, DiscoveryService}
import com.sslproxy.schema.effect.{Lock, Retry, RetryPolicy}
import com.sslproxy.schema.engine.{ApplyCallbacks, MigrationEngine, MigrationPlan}
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.output.{JsonReporter, ReportPrinter}
import com.sslproxy.schema.server.{HttpServer, PostgresDriftCheck}
import io.circe.Json
import io.circe.syntax.*

object Commands:
  private val success = ExitCode.Success
  private val partialFailure = ExitCode(1)
  private val connectionFailure = ExitCode(2)
  private val nonRetryableFailure = ExitCode(3)
  private val validationFailure = ExitCode(4)

  def run(input: (MigratorConfig, CliCommand)): IO[ExitCode] =
    val (config, command) = input
    execute(config, command).handleErrorWith { error =>
      if config.json then
        IO.blocking(System.out.println(Json.obj("error" -> Json.fromString(error.getMessage)).noSpaces)) *> IO.pure(
          exitCodeFor(error)
        )
      else IO.println(s"error: ${error.getMessage}") *> IO.pure(exitCodeFor(error))
    }

  private def execute(config: MigratorConfig, command: CliCommand): IO[ExitCode] =
    IO.defer {
      val validation = validateConfig(config, command)
      IO.fromEither(validation.leftMap(e => MigratorError.Validation(e)))
    }.flatMap { _ =>
      command match
        case CliCommand.Serve =>
          HttpServer.serve(config).as(success)

        case CliCommand.ListFiles =>
          val discovery = DiscoveryService().discover(config.sqlDir, config.dbKind, config.customer)
          discovery
            .flatMap { result =>
              if config.json then JsonReporter.discovery(result) else ReportPrinter.discovery(result)
            }
            .as(success)

        case CliCommand.GenerateBaseline =>
          for
            discovery <- DiscoveryService().discover(config.sqlDir, config.dbKind, config.customer)
            _ <- if config.json then IO.unit else ReportPrinter.warnings(discovery.warnings)
            path <- BaselineGenerator.write(config.sqlDir, config.dbKind, discovery.files)
            _ <-
              if config.json then IO.println(Json.obj("baseline_path" -> path.toString.asJson).noSpaces)
              else IO.println(s"generated baseline: $path")
          yield success

        case CliCommand.Validate =>
          for
            discovery <- DiscoveryService().discover(config.sqlDir, config.dbKind, config.customer)
            plan <- MigrationPlan.inspect(config.dbKind, SqlDialect.forDbKind(config.dbKind), discovery)
            report = plan.validation
            _ <- if config.json then JsonReporter.validation(report) else ReportPrinter.validation(report)
          yield if report.hasErrors then partialFailure else success

        case CliCommand.Apply =>
          if config.dryRun then dryRun(config).as(success)
          else
            withProvider(config) { provider =>
              val engine = MigrationEngine(provider, DiscoveryService())
              engine.apply(config, ApplyCallbacks.console(config)).flatMap { report =>
                val print = if config.json then JsonReporter.applyReport(report) else ReportPrinter.applyReport(report)
                print.as(if report.failedFiles == 0 then success else partialFailure)
              }
            }

        case CliCommand.Status =>
          withSession(config) { session =>
            for
              statuses <- session.fetchStatus
              ready <- session.fetchReady
              _ <- if config.json then JsonReporter.status(statuses, ready) else ReportPrinter.status(statuses, ready)
            yield success
          }

        case CliCommand.Rollback(objectName) =>
          withSession(config) { session =>
            session.bootstrap *>
              Lock
                .use(session.acquireLock, session.releaseLock)(session.rollbackObject(config.sqlDir, objectName))
                .as(
                  success
                )
          }

        case CliCommand.Ready(strict) =>
          withSession(config) { session =>
            session.fetchReady.flatMap { ready =>
              val print =
                if config.json then JsonReporter.ready(ready)
                else if ready.ready then IO.println("schema ready")
                else IO.println("schema not ready")
              print.as(if ready.ready || !strict then success else partialFailure)
            }
          }

        case CliCommand.DriftCheck =>
          PostgresDriftCheck.run(config).flatMap { response =>
            val print = if config.json then JsonReporter.drift(response) else ReportPrinter.drift(response)
            print.as(if response.supported && response.items.isEmpty then success else partialFailure)
          }

        case CliCommand.CheckConnection =>
          withSession(config)(_.checkConnection.as(success))
    }

  private def validateConfig(config: MigratorConfig, command: CliCommand): Either[String, Unit] =
    command match
      case CliCommand.Serve => config.validateServer
      case CliCommand.ListFiles | CliCommand.GenerateBaseline | CliCommand.Validate => config.validateSqlOnly
      case CliCommand.Apply if config.dryRun => config.validateSqlOnly
      case _ => config.validate

  private def withProvider[A](config: MigratorConfig)(use: DbProvider => IO[A]): IO[A] =
    providerFor(config).flatMap(use)

  private def withSession[A](config: MigratorConfig)(use: com.sslproxy.schema.db.DbSession => IO[A]): IO[A] =
    providerFor(config).flatMap { provider =>
      Retry.withBackoff(
        RetryPolicy(config.connectRetries + 1, config.connectRetryBackoff),
        MigratorError.isConnectionFailure
      )(provider.session.use(use))
    }

  private def providerFor(config: MigratorConfig): IO[DbProvider] =
    config.dbKind match
      case DbKind.Postgres => PostgresProvider.fromConfig(config)
      case DbKind.Oracle => OracleProvider.fromConfig(config)
      case DbKind.TiDB => TiDBProvider.fromConfig(config)

  private def exitCodeFor(error: Throwable): ExitCode =
    if MigratorError.isConnectionFailure(error) then connectionFailure
    else if MigratorError.isNonRetryableApply(error) then nonRetryableFailure
    else if MigratorError.isValidation(error) then validationFailure
    else partialFailure

  private def dryRun(config: MigratorConfig): IO[Unit] =
    for
      plan <- MigrationPlan.prepare(config, SqlDialect.forDbKind(config.dbKind), DiscoveryService())
      _ <- if config.json then IO.unit else ReportPrinter.warnings(plan.warnings)
      previews <- plan.files.traverse { file =>
        IO.blocking {
          val sql = file.readString
          val compact = sql.split("\\s+").filter(_.nonEmpty).mkString(" ")
          val preview = if compact.length <= 120 then compact else compact.take(119) + "..."
          file.relativePath -> preview
        }
      }
      _ <- ReportPrinter.dryRun(previews)
    yield ()
