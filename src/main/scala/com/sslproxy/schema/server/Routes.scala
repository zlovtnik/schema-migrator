package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.store.{PatchStore, RunStore, TargetStore, ValidationStore}
import org.http4s.HttpRoutes

object Routes:
  def all(
    config: MigratorConfig,
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore
  ): HttpRoutes[IO] =
    HealthRoute.routes <+>
      AuthRoutes.routes(config.server) <+>
      TargetRoutes.routes(config.server, targetStore, patchStore, runStore, validationStore) <+>
      SchemaRoutes.routes(config, targetStore) <+>
      PatchRoutes.routes(targetStore, patchStore) <+>
      RunRoutes.routes(targetStore, patchStore, runStore, validationStore) <+>
      ValidationRoutes.routes(runStore, validationStore)
