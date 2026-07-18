package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.discovery.{GitRepoLoader, RepoSyncService}
import com.sslproxy.schema.store.{
  AuditStore,
  PatchStore,
  RunStore,
  SnapshotStore,
  SqlFileStore,
  TargetStore,
  ValidationStore
}
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
    auditStore: AuditStore,
    runExecutor: RunExecutor
  ): HttpRoutes[IO] =
    val loader = GitRepoLoader(config.server.repoCloneTimeoutSeconds)
    all(
      config,
      targetStore,
      patchStore,
      runStore,
      validationStore,
      sqlFileStore,
      snapshotStore,
      auditStore,
      runExecutor,
      loader,
      RepoSyncService(
        sqlFileStore,
        loader,
        config.server.repoCacheDir,
        config.server.repoCloneTimeoutSeconds,
        targetStore
      )
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
    runExecutor: RunExecutor,
    gitRepoLoader: GitRepoLoader,
    repoSyncService: RepoSyncService
  ): HttpRoutes[IO] =
    HealthRoute.routes <+>
      AuthRoutes.routes(config.server) <+>
      TargetRoutes.routes(
        config.server,
        targetStore,
        patchStore,
        runStore,
        validationStore,
        auditStore
      ) <+>
      SchemaRoutes.routes(config, targetStore, sqlFileStore, patchStore, runStore, auditStore, runExecutor) <+>
      PatchRoutes.routes(targetStore, patchStore, sqlFileStore, auditStore) <+>
      RunRoutes.routes(config, targetStore, patchStore, runStore, auditStore, runExecutor) <+>
      ValidationRoutes.routes(config, targetStore, patchStore, runStore, sqlFileStore, validationStore, auditStore) <+>
      SnapshotRoutes.routes(
        config,
        targetStore,
        patchStore,
        runStore,
        sqlFileStore,
        snapshotStore,
        auditStore,
        runExecutor
      ) <+>
      AuditRoutes.routes(auditStore) <+>
      SqlFileRoutes.routes(sqlFileStore, targetStore, repoSyncService, gitRepoLoader)
