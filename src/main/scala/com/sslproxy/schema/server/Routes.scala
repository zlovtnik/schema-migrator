package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.ServerConfig
import com.sslproxy.schema.store.{PatchStore, RunStore, TargetStore, ValidationStore}
import org.http4s.HttpRoutes

object Routes:
  def all(
    config: ServerConfig,
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore
  ): HttpRoutes[IO] =
    HealthRoute.routes <+>
      AuthRoutes.routes(config) <+>
      TargetRoutes.routes(targetStore) <+>
      PatchRoutes.routes(targetStore, patchStore) <+>
      RunRoutes.routes(targetStore, patchStore, runStore, validationStore) <+>
      ValidationRoutes.routes(runStore, validationStore)
