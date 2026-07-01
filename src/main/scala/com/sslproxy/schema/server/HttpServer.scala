package com.sslproxy.schema.server

import cats.effect.{IO, Resource}
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
    serverResource(config).use(_ =>
      IO.println(
        s"schema-migrator API listening on http://${config.server.host}:${config.server.port}/api"
      ) *> IO.never
    )

  private def serverResource(config: MigratorConfig): Resource[IO, org.http4s.server.Server] =
    for
      host <- Resource.eval(IO.fromOption(Host.fromString(config.server.host))(
        IllegalArgumentException(s"invalid host '${config.server.host}'")
      ))
      port <- Resource.eval(IO.fromOption(Port.fromInt(config.server.port))(
        IllegalArgumentException(s"invalid port '${config.server.port}'")
      ))
      encryptKey <- Resource.eval(IO.fromEither(
        config.server.encryptKeyBase64
          .traverse(AesGcm.keyFromBase64)
          .leftMap(message => new IllegalArgumentException(message))
      ))
      mongoConfig <- Resource.eval(
        IO.fromEither(config.server.mongoConfig.leftMap(message => new IllegalArgumentException(message)))
      )
      targetStore <- TargetStore.mongo(mongoConfig)
      patchStore <- Resource.eval(PatchStore.inMemory(config.server.patchStageDir))
      runStore <- Resource.eval(RunStore.inMemory)
      validationStore <- Resource.eval(ValidationStore.inMemory)
      apiRoutes = Routes.all(config, targetStore, patchStore, runStore, validationStore)
      routed = Router("/api" -> apiRoutes)
      authed = JwtMiddleware(config.server)(routed)
      encrypted = AesGcmMiddleware(encryptKey)(authed)
      compressed = Bzip2Middleware(encrypted)
      withCors = CorsMiddleware(config.server)(compressed)
      logged = LoggingMiddleware(withCors.orNotFound)
      httpApp = logged
      server <- EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
    yield server
