package com.sslproxy.schema

import cats.effect.{ExitCode, IO}
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import com.sslproxy.schema.cli.{CliOpts, Commands}

object Main
    extends CommandIOApp(
      name = "schema-migrator",
      header = "Unified schema migrator for Postgres and Oracle"
    ):
  override def main: Opts[IO[ExitCode]] =
    CliOpts.opts.map(Commands.run)
