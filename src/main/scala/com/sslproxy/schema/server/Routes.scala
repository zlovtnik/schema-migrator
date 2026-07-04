package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.store.{AuditStore, PatchStore, RunStore, SnapshotStore, SqlFileStore, TargetStore, ValidationStore}
import org.http4s.HttpRoutes

object Routes:
  def all(
    config: MigratorConfig,
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore,
    sqlFileStore: SqlFileStore,
    snapshotStore: SnapshotStore,
    auditStore: AuditStore
  ): HttpRoutes[IO] =
    all(
      config,
      targetStore,
      patchStore,
      runStore,
      validationStore,
      sqlFileStore,
      snapshotStore,
      auditStore,
      RunExecutor.real(config, patchStore, runStore, validationStore, Some(auditStore))
    )

  def all(
    config: MigratorConfig,
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore,
    sqlFileStore: SqlFileStore,
    snapshotStore: SnapshotStore,
    auditStore: AuditStore,
    runExecutor: RunExecutor
  ): HttpRoutes[IO] =
    HealthRoute.routes <+>
      AuthRoutes.routes(config.server) <+>
      TargetRoutes.routes(config.server, targetStore, patchStore, runStore, validationStore, auditStore) <+>
      SchemaRoutes.routes(config, targetStore, sqlFileStore) <+>
      PatchRoutes.routes(targetStore, patchStore, auditStore) <+>
      RunRoutes.routes(config, targetStore, patchStore, runStore, validationStore, auditStore, runExecutor) <+>
      ValidationRoutes.routes(targetStore, patchStore, runStore, validationStore, auditStore) <+>
      SnapshotRoutes.routes(config, targetStore, patchStore, runStore, sqlFileStore, snapshotStore, auditStore, runExecutor) <+>
      AuditRoutes.routes(auditStore) <+>
      SqlFileRoutes.routes(sqlFileStore)
