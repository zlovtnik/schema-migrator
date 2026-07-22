package com.sslproxy.schema.effect

import cats.effect.IO
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Lock:
  private val logger = Slf4jLogger.getLogger[IO]

  def use[A](acquire: IO[Unit], release: IO[Unit])(action: IO[A]): IO[A] =
    acquire.bracket(_ => action)(_ =>
      release.handleErrorWith(error => logger.warn(error)("schema lock release failed") *> IO.raiseError(error))
    )
