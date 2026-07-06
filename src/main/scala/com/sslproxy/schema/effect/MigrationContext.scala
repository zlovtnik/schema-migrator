package com.sslproxy.schema.effect

import cats.Applicative
import cats.mtl.Ask
import com.sslproxy.schema.config.{DbKind, MigratorConfig}

/** Metadata that describes the migration currently being evaluated.
  *
  * CLI runs usually have no `runId` or `targetId`; server-triggered
  * runs do. Keeping this in an effect context lets code that only
  * needs metadata avoid carrying the full `MigratorConfig`.
  */
final case class MigrationRunContext(
  runId: Option[String],
  targetId: Option[String],
  dbKind: DbKind,
  customer: Option[String],
  dryRun: Boolean
)

object MigrationRunContext:
  def fromConfig(config: MigratorConfig): MigrationRunContext =
    MigrationRunContext(
      runId = None,
      targetId = None,
      dbKind = config.dbKind,
      customer = config.customer,
      dryRun = config.dryRun
    )

/** Domain effect for reading migration metadata. */
trait MigrationContext[F[_]]:
  def current: F[MigrationRunContext]

object MigrationContext:
  def apply[F[_]](using ev: MigrationContext[F]): MigrationContext[F] = ev

  def fixed[F[_]: Applicative](context: MigrationRunContext): MigrationContext[F] =
    new MigrationContext[F]:
      def current: F[MigrationRunContext] =
        Applicative[F].pure(context)

  given fromAsk[F[_]](using ask: Ask[F, MigrationRunContext]): MigrationContext[F] with
    def current: F[MigrationRunContext] =
      ask.ask
