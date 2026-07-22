package com.sslproxy.schema.store

import cats.effect.IO
import com.sslproxy.schema.config.ServerConfig

object KeycloakConfigStore:
  def persist(config: ServerConfig, database: StateDatabase): IO[Unit] =
    TiDBKeycloakConfigStore.persist(config, database)
