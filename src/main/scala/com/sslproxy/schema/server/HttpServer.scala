package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.server.auth.JwtMiddleware
import com.sslproxy.schema.server.compress.Bzip2Middleware
import com.sslproxy.schema.server.crypto.{AesGcm, AesGcmMiddleware}
import com.sslproxy.schema.store.{PatchStore, RunStore, TargetStore, ValidationStore}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

object HttpServer:
  def serve(config: MigratorConfig): IO[Unit] =
    for
      host <- IO.fromOption(Host.fromString(config.server.host))(IllegalArgumentException(s"invalid host '${config.server.host}'"))
      port <- IO.fromOption(Port.fromInt(config.server.port))(IllegalArgumentException(s"invalid port '${config.server.port}'"))
      encryptKey <- IO.fromEither(
        config.server.encryptKeyBase64.traverse(AesGcm.keyFromBase64).leftMap(message => new IllegalArgumentException(message))
      )
      targetStore <- TargetStore.inMemory
      patchStore <- PatchStore.inMemory(config.server.patchStageDir)
      runStore <- RunStore.inMemory
      validationStore <- ValidationStore.inMemory
      apiRoutes = Routes.all(config.server, targetStore, patchStore, runStore, validationStore)
      routed = Router("/api" -> apiRoutes)
      authed = JwtMiddleware(config.server)(routed)
      encrypted = AesGcmMiddleware(encryptKey)(authed)
      compressed = Bzip2Middleware(encrypted)
      withCors = CorsMiddleware(config.server)(compressed)
      httpApp = withCors.orNotFound
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .use(_ => IO.println(s"schema-migrator API listening on http://${config.server.host}:${config.server.port}/api") *> IO.never)
    yield ()
