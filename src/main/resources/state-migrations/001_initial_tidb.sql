-- object: schema_migrator_database
-- depends_on:
-- Runtime identities are provisioned outside this migration.

CREATE DATABASE IF NOT EXISTS schema_migrator
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE schema_migrator;
-- object: schema_migrator_schema_control
-- depends_on: schema_migrator_database

USE schema_migrator;

CREATE TABLE IF NOT EXISTS state_schema_migrations (
  version    VARCHAR(128) NOT NULL,
  checksum   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  applied_by VARCHAR(128) NOT NULL DEFAULT 'schema-migrator',
  PRIMARY KEY (version)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS schema_readiness (
  domain            VARCHAR(64) NOT NULL,
  required_version  VARCHAR(64) NOT NULL,
  applied_version   VARCHAR(64) DEFAULT NULL,
  required_checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  applied_checksum  CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  ready             TINYINT(1) NOT NULL DEFAULT 0,
  details           JSON DEFAULT NULL,
  checked_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (domain),
  CONSTRAINT schema_migrator_schema_ready_ck CHECK (
    ready = 0 OR (
      applied_version = required_version
      AND applied_checksum = required_checksum
    )
  )
) ENGINE=InnoDB;

INSERT INTO schema_readiness (
  domain, required_version, applied_version, required_checksum,
  applied_checksum, ready, details
) VALUES (
  'schema_migrator',
  '001',
  NULL,
  '0000000000000000000000000000000000000000000000000000000000000000',
  NULL,
  0,
  JSON_OBJECT('state', 'awaiting-manifest-verification')
) ON DUPLICATE KEY UPDATE domain = VALUES(domain);
-- object: schema_migrator_repository_state
-- depends_on: schema_migrator_schema_control

USE schema_migrator;

CREATE TABLE IF NOT EXISTS targets (
  id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  label               VARCHAR(255) NOT NULL,
  app_name            VARCHAR(255) NOT NULL,
  environment         VARCHAR(128) NOT NULL,
  jdbc_url            TEXT NOT NULL,
  db_kind             VARCHAR(32) NOT NULL,
  created_at          DATETIME(6) NOT NULL,
  updated_at          DATETIME(6) NOT NULL,
  repo_url            TEXT NOT NULL,
  repo_branch         VARCHAR(255) NOT NULL,
  repo_sql_path       VARCHAR(1024) NOT NULL,
  last_synced_commit  VARCHAR(128) DEFAULT NULL,
  last_synced_at      DATETIME(6) DEFAULT NULL,
  password_ciphertext BLOB DEFAULT NULL,
  password_iv         BLOB DEFAULT NULL,
  PRIMARY KEY (id),
  KEY targets_created_at_idx (created_at),
  CONSTRAINT targets_password_complete_ck CHECK (
    (password_ciphertext IS NULL AND password_iv IS NULL)
    OR
    (password_ciphertext IS NOT NULL AND password_iv IS NOT NULL)
  )
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sql_files (
  target_id   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  path        VARCHAR(512) NOT NULL,
  folder      VARCHAR(128) NOT NULL,
  filename    VARCHAR(255) NOT NULL,
  content     LONGBLOB NOT NULL,
  sha256      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  uploaded_at DATETIME(6) NOT NULL,
  PRIMARY KEY (target_id, path),
  KEY sql_files_folder_filename_idx (target_id, folder, filename)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS patches (
  id                 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version            VARCHAR(128) NOT NULL,
  label              VARCHAR(255) NOT NULL,
  status             VARCHAR(32) NOT NULL,
  applied_at         DATETIME(6) DEFAULT NULL,
  source_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY patches_target_version_uq (target_id, version),
  KEY patches_status_idx (status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS patch_scripts (
  id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  patch_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  script_order INT NOT NULL,
  filename     VARCHAR(255) NOT NULL,
  checksum     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status       VARCHAR(32) NOT NULL,
  error        JSON DEFAULT NULL,
  duration_ms  BIGINT DEFAULT NULL,
  content      LONGBLOB NOT NULL,
  created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY patch_scripts_order_uq (patch_id, script_order),
  KEY patch_scripts_status_idx (patch_id, status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS snapshots (
  id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_id  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  label      VARCHAR(255) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  created_by VARCHAR(255) NOT NULL,
  file_count INT NOT NULL,
  PRIMARY KEY (id),
  KEY snapshots_target_created_idx (target_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS snapshot_files (
  snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  path        VARCHAR(512) NOT NULL,
  folder      VARCHAR(128) NOT NULL,
  filename    VARCHAR(255) NOT NULL,
  sha256      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content     LONGBLOB NOT NULL,
  uploaded_at DATETIME(6) NOT NULL,
  size_bytes  BIGINT NOT NULL,
  PRIMARY KEY (snapshot_id, path)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS repository_state (
  target_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  last_scanned_commit VARCHAR(128) DEFAULT NULL,
  manifest_sha256    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  file_count         INT NOT NULL DEFAULT 0,
  status             VARCHAR(32) NOT NULL DEFAULT 'empty',
  last_error         TEXT DEFAULT NULL,
  scanned_at         DATETIME(6) DEFAULT NULL,
  updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (target_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS keycloak_config (
  id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  enabled    TINYINT(1) NOT NULL,
  issuer     TEXT DEFAULT NULL,
  jwks_uri   TEXT DEFAULT NULL,
  client_id  VARCHAR(255) DEFAULT NULL,
  audience   VARCHAR(255) DEFAULT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB;
-- object: schema_migrator_run_state
-- depends_on: schema_migrator_repository_state

USE schema_migrator;

CREATE TABLE IF NOT EXISTS runs (
  id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  patch_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status           VARCHAR(32) NOT NULL,
  started_at       DATETIME(6) NOT NULL,
  ended_at         DATETIME(6) DEFAULT NULL,
  triggered_by     VARCHAR(255) NOT NULL,
  owner_id         VARCHAR(128) DEFAULT NULL,
  lease_token      CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  lease_fence      BIGINT NOT NULL DEFAULT 0,
  lease_expires_at DATETIME(6) DEFAULT NULL,
  attempt_count    INT NOT NULL DEFAULT 0,
  max_attempts     INT NOT NULL DEFAULT 3,
  next_attempt_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_error       TEXT DEFAULT NULL,
  updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  active_flag      TINYINT GENERATED ALWAYS AS (
    CASE WHEN status IN ('pending', 'running') THEN 1 ELSE NULL END
  ) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY runs_one_active_per_target_uq (target_id, active_flag),
  KEY runs_target_started_idx (target_id, started_at),
  KEY runs_claim_idx (status, next_attempt_at, lease_expires_at),
  CONSTRAINT runs_attempts_ck CHECK (
    attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts
  )
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS run_scripts (
  run_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  script_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  filename     VARCHAR(255) NOT NULL,
  script_order INT NOT NULL,
  status       VARCHAR(32) NOT NULL,
  error        JSON DEFAULT NULL,
  duration_ms  BIGINT DEFAULT NULL,
  started_at   DATETIME(6) DEFAULT NULL,
  finished_at  DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (run_id, script_id),
  UNIQUE KEY run_scripts_order_uq (run_id, script_order)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS validations (
  run_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_id  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  checked_at DATETIME(6) NOT NULL,
  status     VARCHAR(32) NOT NULL,
  PRIMARY KEY (run_id),
  KEY validations_target_checked_idx (target_id, checked_at),
  KEY validations_status_idx (status, checked_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS validation_issues (
  run_id      CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  issue_order INT NOT NULL,
  object_type VARCHAR(128) NOT NULL,
  schema_name VARCHAR(255) NOT NULL,
  object_name VARCHAR(255) NOT NULL,
  error       TEXT NOT NULL,
  severity    VARCHAR(32) NOT NULL,
  PRIMARY KEY (run_id, issue_order)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS audit_events (
  id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor       VARCHAR(255) NOT NULL,
  role        VARCHAR(128) NOT NULL,
  action      VARCHAR(128) NOT NULL,
  entity_type VARCHAR(128) NOT NULL,
  entity_id   VARCHAR(255) NOT NULL,
  target_id   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  at          DATETIME(6) NOT NULL,
  metadata    JSON DEFAULT NULL,
  PRIMARY KEY (id),
  KEY audit_events_at_idx (at),
  KEY audit_events_actor_idx (actor, at),
  KEY audit_events_entity_idx (entity_type, entity_id, at),
  KEY audit_events_target_idx (target_id, at)
) ENGINE=InnoDB;
-- object: schema_migrator_control_leases
-- depends_on: schema_migrator_run_state
-- Claims use conditional UPDATE plus affected-row checks. The fence increases
-- on every successful claim; stale owners cannot complete newer work.

USE schema_migrator;

CREATE TABLE IF NOT EXISTS control_leases (
  resource_type    VARCHAR(64) NOT NULL,
  resource_id      VARCHAR(255) NOT NULL,
  owner_id         VARCHAR(128) DEFAULT NULL,
  lease_token      CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  fence            BIGINT NOT NULL DEFAULT 0,
  attempt_count    INT NOT NULL DEFAULT 0,
  lease_expires_at DATETIME(6) DEFAULT NULL,
  next_attempt_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_error       TEXT DEFAULT NULL,
  updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (resource_type, resource_id),
  KEY control_leases_claim_idx (resource_type, next_attempt_at, lease_expires_at),
  CONSTRAINT control_leases_attempt_count_ck CHECK (attempt_count >= 0),
  CONSTRAINT control_leases_owner_token_ck CHECK (
    (owner_id IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
    OR
    (owner_id IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
  )
) ENGINE=InnoDB;
