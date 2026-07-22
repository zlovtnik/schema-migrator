package com.sslproxy.schema.server

import cats.effect.{IO, Resource}
import cats.effect.std.Supervisor
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.server.auth.{JwtMiddleware, KeycloakJwks}
import com.sslproxy.schema.server.compress.Bzip2Middleware
import com.sslproxy.schema.server.crypto.{AesGcm, AesGcmMiddleware}
import com.sslproxy.schema.store.{KeycloakConfigStore, StateDatabase, TiDBStores}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.server.Router
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import scala.concurrent.duration.*

object HttpServer:
  private given LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val logger = LoggerFactory[IO].getLogger

  def serve(config: MigratorConfig): IO[Unit] =
    serverResource(config).use(_ =>
      logger.info(s"schema-migrator API listening on http://${config.server.host}:${config.server.port}/api") *>
        IO.never
    )

  private def serverResource(config: MigratorConfig): Resource[IO, org.http4s.server.Server] =
    for
      host <- Resource.eval(
        IO.fromOption(Host.fromString(config.server.host))(
          IllegalArgumentException(s"invalid host '${config.server.host}'")
        )
      )
      port <- Resource.eval(
        IO.fromOption(Port.fromInt(config.server.port))(
          IllegalArgumentException(s"invalid port '${config.server.port}'")
        )
      )
      encryptKey <- Resource.eval(
        IO.fromEither(
          config.server.encryptKeyBase64
            .traverse(AesGcm.keyFromBase64)
            .leftMap(message => new IllegalArgumentException(message))
        )
      )
      encryptKeyRing = encryptKey.map(key => AesGcm.KeyRing("current", key, Map.empty))
      stateStoreConfig <- Resource.eval(
        IO.fromEither(config.server.stateStoreConfig.leftMap(message => new IllegalArgumentException(message)))
      )
      stateDatabase <- StateDatabase.resource(stateStoreConfig)
      targetPasswordKey <- Resource.eval(
        IO.fromOption(encryptKey)(
          IllegalArgumentException("BEDROCK_ENCRYPT_KEY is required to encrypt stored target passwords")
        )
      )
      stores <- TiDBStores.resource(stateDatabase, targetPasswordKey)
      targetStore = stores.targetStore
      sqlFileStore = stores.sqlFileStore
      patchStore = stores.patchStore
      runStore = stores.runStore
      validationStore = stores.validationStore
      snapshotStore = stores.snapshotStore
      auditStore = stores.auditStore
      supervisor <- Supervisor[IO]
      runExecutor = RunExecutor.supervised(
        RunExecutor.real(config, patchStore, runStore, validationStore, Some(auditStore)),
        supervisor,
        runStore
      )
      _ <- Resource.make(
        supervisor.supervise(
          (runExecutor
            .recoverPending(targetStore, patchStore)
            .handleErrorWith(error => logger.error(error)("durable run recovery scan failed")) *>
            IO.sleep(1.second)).foreverM
        )
      )(_.cancel)
      _ <- Resource.eval(
        KeycloakConfigStore.persist(config.server, stateDatabase)
      )
      keycloakVerifier <- keycloakVerifierResource(config)
      apiRoutes = Routes.all(
        config,
        targetStore,
        patchStore,
        runStore,
        validationStore,
        sqlFileStore,
        snapshotStore,
        auditStore,
        runExecutor
      )
      routed = Router("/api" -> apiRoutes)
      authed = JwtMiddleware(config.server, keycloakVerifier)(routed)
      encrypted = AesGcmMiddleware(encryptKeyRing)(authed)
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

  private def keycloakVerifierResource(config: MigratorConfig): Resource[IO, Option[JwtMiddleware.TokenVerifier]] =
    if !config.server.keycloakEnabled then Resource.pure[IO, Option[JwtMiddleware.TokenVerifier]](None)
    else
      for
        client <- EmberClientBuilder.default[IO].build
        verifier <- Resource.eval(KeycloakJwks.create(config.server, client))
      yield Some(verifier.verify)
