package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.discovery.{GitRepoLoader, RepoSyncService}
import com.sslproxy.schema.store.{AuditStore, PatchStore, RepoSyncStore, RunStore, SnapshotStore, SqlFileStore, TargetStore, ValidationStore}
import org.http4s.HttpRoutes

object Routes:
  def all(
    config: MigratorConfig,
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore,
    sqlFileStore: SqlFileStore,
    repoSyncStore: RepoSyncStore,
    snapshotStore: SnapshotStore,
    auditStore: AuditStore
  ): HttpRoutes[IO] =
    val loader = GitRepoLoader()
    all(
      config,
      targetStore,
      patchStore,
      runStore,
      validationStore,
      sqlFileStore,
      repoSyncStore,
      snapshotStore,
      auditStore,
      RunExecutor.real(config, patchStore, runStore, validationStore, Some(auditStore)),
      loader,
      RepoSyncService(
        sqlFileStore,
        repoSyncStore,
        loader,
        config.server.repoCacheDir,
        config.server.repoCloneTimeoutSeconds
      )
    )

  def all(
    config: MigratorConfig,
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore,
    sqlFileStore: SqlFileStore,
    repoSyncStore: RepoSyncStore,
    snapshotStore: SnapshotStore,
    auditStore: AuditStore,
    runExecutor: RunExecutor
  ): HttpRoutes[IO] =
    val loader = GitRepoLoader()
    all(
      config,
      targetStore,
      patchStore,
      runStore,
      validationStore,
      sqlFileStore,
      repoSyncStore,
      snapshotStore,
      auditStore,
      runExecutor,
      loader,
      RepoSyncService(
        sqlFileStore,
        repoSyncStore,
        loader,
        config.server.repoCacheDir,
        config.server.repoCloneTimeoutSeconds
      )
    )

  def all(
    config: MigratorConfig,
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore,
    sqlFileStore: SqlFileStore,
    repoSyncStore: RepoSyncStore,
    snapshotStore: SnapshotStore,
    auditStore: AuditStore,
    runExecutor: RunExecutor,
    gitRepoLoader: GitRepoLoader,
    repoSyncService: RepoSyncService
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
      SqlFileRoutes.routes(sqlFileStore, targetStore, repoSyncStore, repoSyncService, gitRepoLoader)
