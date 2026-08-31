-- object: schema_migrator_schema
-- depends_on:
-- Runtime identities are provisioned outside this migration.

CREATE SCHEMA IF NOT EXISTS schema_migrator;
-- object: schema_migrator_schema_control
-- depends_on: schema_migrator_schema

CREATE TABLE IF NOT EXISTS schema_migrator.state_schema_migrations (
  version    VARCHAR(128) NOT NULL,
  checksum   char(64) NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  applied_by VARCHAR(128) NOT NULL DEFAULT 'schema-migrator',
  PRIMARY KEY (version)
);

CREATE TABLE IF NOT EXISTS schema_migrator.schema_readiness (
  domain            VARCHAR(64) NOT NULL,
  required_version  VARCHAR(64) NOT NULL,
  applied_version   VARCHAR(64) DEFAULT NULL,
  required_checksum char(64) NOT NULL,
  applied_checksum  char(64) DEFAULT NULL,
  ready             boolean NOT NULL DEFAULT false,
  details           jsonb DEFAULT NULL,
  checked_at        timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (domain),
  CONSTRAINT schema_migrator_schema_ready_ck CHECK (
    ready = false OR (
      applied_version = required_version
      AND applied_checksum = required_checksum
    )
  )
);

INSERT INTO schema_migrator.schema_readiness (
  domain, required_version, applied_version, required_checksum,
  applied_checksum, ready, details
) VALUES (
  'schema_migrator',
  '001',
  NULL,
  '0000000000000000000000000000000000000000000000000000000000000000',
  NULL,
  false,
  jsonb_build_object('state', 'awaiting-manifest-verification')
) ON CONFLICT DO NOTHING;
-- object: schema_migrator_repository_state
-- depends_on: schema_migrator_schema_control

CREATE TABLE IF NOT EXISTS schema_migrator.targets (
  id                  uuid NOT NULL,
  label               VARCHAR(255) NOT NULL,
  app_name            VARCHAR(255) NOT NULL,
  environment         VARCHAR(128) NOT NULL,
  jdbc_url            TEXT NOT NULL,
  db_kind             VARCHAR(32) NOT NULL,
  created_at          timestamptz NOT NULL,
  updated_at          timestamptz NOT NULL,
  repo_url            TEXT NOT NULL,
  repo_branch         VARCHAR(255) NOT NULL,
  repo_sql_path       VARCHAR(1024) NOT NULL,
  last_synced_commit  VARCHAR(128) DEFAULT NULL,
  last_synced_at      timestamptz DEFAULT NULL,
  password_ciphertext bytea DEFAULT NULL,
  password_iv         bytea DEFAULT NULL,
  PRIMARY KEY (id),
  CONSTRAINT targets_password_complete_ck CHECK (
    (password_ciphertext IS NULL AND password_iv IS NULL)
    OR
    (password_ciphertext IS NOT NULL AND password_iv IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS targets_created_at_idx ON schema_migrator.targets (created_at);

CREATE TABLE IF NOT EXISTS schema_migrator.sql_files (
  target_id   uuid NOT NULL,
  path        VARCHAR(512) NOT NULL,
  folder      VARCHAR(128) NOT NULL,
  filename    VARCHAR(255) NOT NULL,
  content     bytea NOT NULL,
  sha256      char(64) NOT NULL,
  uploaded_at timestamptz NOT NULL,
  PRIMARY KEY (target_id, path)
);

CREATE INDEX IF NOT EXISTS sql_files_folder_filename_idx ON schema_migrator.sql_files (target_id, folder, filename);

CREATE TABLE IF NOT EXISTS schema_migrator.patches (
  id                 uuid NOT NULL,
  target_id          uuid NOT NULL,
  version            VARCHAR(128) NOT NULL,
  label              VARCHAR(255) NOT NULL,
  status             VARCHAR(32) NOT NULL,
  applied_at         timestamptz DEFAULT NULL,
  source_snapshot_id uuid DEFAULT NULL,
  created_at         timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT patches_target_version_uq UNIQUE (target_id, version)
);

CREATE INDEX IF NOT EXISTS patches_status_idx ON schema_migrator.patches (status);

CREATE TABLE IF NOT EXISTS schema_migrator.patch_scripts (
  id           uuid NOT NULL,
  patch_id     uuid NOT NULL,
  script_order INT NOT NULL,
  filename     VARCHAR(255) NOT NULL,
  checksum     char(64) NOT NULL,
  status       VARCHAR(32) NOT NULL,
  error        jsonb DEFAULT NULL,
  duration_ms  BIGINT DEFAULT NULL,
  content      bytea NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT patch_scripts_order_uq UNIQUE (patch_id, script_order)
);

CREATE INDEX IF NOT EXISTS patch_scripts_status_idx ON schema_migrator.patch_scripts (patch_id, status);

CREATE TABLE IF NOT EXISTS schema_migrator.snapshots (
  id         uuid NOT NULL,
  target_id  uuid NOT NULL,
  label      VARCHAR(255) NOT NULL,
  created_at timestamptz NOT NULL,
  created_by VARCHAR(255) NOT NULL,
  file_count INT NOT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS snapshots_target_created_idx ON schema_migrator.snapshots (target_id, created_at);

CREATE TABLE IF NOT EXISTS schema_migrator.snapshot_files (
  snapshot_id uuid NOT NULL,
  path        VARCHAR(512) NOT NULL,
  folder      VARCHAR(128) NOT NULL,
  filename    VARCHAR(255) NOT NULL,
  sha256      char(64) NOT NULL,
  content     bytea NOT NULL,
  uploaded_at timestamptz NOT NULL,
  size_bytes  BIGINT NOT NULL,
  PRIMARY KEY (snapshot_id, path)
);

CREATE TABLE IF NOT EXISTS schema_migrator.repository_state (
  target_id          uuid NOT NULL,
  last_scanned_commit VARCHAR(128) DEFAULT NULL,
  manifest_sha256    char(64) DEFAULT NULL,
  file_count         INT NOT NULL DEFAULT 0,
  status             VARCHAR(32) NOT NULL DEFAULT 'empty',
  last_error         TEXT DEFAULT NULL,
  scanned_at         timestamptz DEFAULT NULL,
  updated_at         timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (target_id)
);

CREATE TABLE IF NOT EXISTS schema_migrator.keycloak_config (
  id         uuid NOT NULL,
  enabled    boolean NOT NULL,
  issuer     TEXT DEFAULT NULL,
  jwks_uri   TEXT DEFAULT NULL,
  client_id  VARCHAR(255) DEFAULT NULL,
  audience   VARCHAR(255) DEFAULT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (id)
);
-- object: schema_migrator_run_state
-- depends_on: schema_migrator_repository_state

CREATE TABLE IF NOT EXISTS schema_migrator.runs (
  id               uuid NOT NULL,
  target_id        uuid NOT NULL,
  patch_id         uuid NOT NULL,
  status           VARCHAR(32) NOT NULL,
  started_at       timestamptz NOT NULL,
  ended_at         timestamptz DEFAULT NULL,
  triggered_by     VARCHAR(255) NOT NULL,
  owner_id         VARCHAR(128) DEFAULT NULL,
  lease_token      uuid DEFAULT NULL,
  lease_fence      BIGINT NOT NULL DEFAULT 0,
  lease_expires_at timestamptz DEFAULT NULL,
  attempt_count    INT NOT NULL DEFAULT 0,
  max_attempts     INT NOT NULL DEFAULT 3,
  next_attempt_at  timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error       TEXT DEFAULT NULL,
  updated_at       timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  active_flag      smallint GENERATED ALWAYS AS (
    CASE WHEN status IN ('pending', 'running') THEN 1 ELSE NULL END
  ) STORED,
  PRIMARY KEY (id),
  CONSTRAINT runs_one_active_per_target_uq UNIQUE (target_id, active_flag),
  CONSTRAINT runs_attempts_ck CHECK (
    attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts
  )
);

CREATE INDEX IF NOT EXISTS runs_target_started_idx ON schema_migrator.runs (target_id, started_at);
CREATE INDEX IF NOT EXISTS runs_claim_idx ON schema_migrator.runs (status, next_attempt_at, lease_expires_at);

CREATE TABLE IF NOT EXISTS schema_migrator.run_scripts (
  run_id       uuid NOT NULL,
  script_id    uuid NOT NULL,
  filename     VARCHAR(255) NOT NULL,
  script_order INT NOT NULL,
  status       VARCHAR(32) NOT NULL,
  error        jsonb DEFAULT NULL,
  duration_ms  BIGINT DEFAULT NULL,
  started_at   timestamptz DEFAULT NULL,
  finished_at  timestamptz DEFAULT NULL,
  PRIMARY KEY (run_id, script_id),
  CONSTRAINT run_scripts_order_uq UNIQUE (run_id, script_order)
);

CREATE TABLE IF NOT EXISTS schema_migrator.validations (
  run_id     uuid NOT NULL,
  target_id  uuid NOT NULL,
  checked_at timestamptz NOT NULL,
  status     VARCHAR(32) NOT NULL,
  PRIMARY KEY (run_id)
);

CREATE INDEX IF NOT EXISTS validations_target_checked_idx ON schema_migrator.validations (target_id, checked_at);
CREATE INDEX IF NOT EXISTS validations_status_idx ON schema_migrator.validations (status, checked_at);

CREATE TABLE IF NOT EXISTS schema_migrator.validation_issues (
  run_id      uuid NOT NULL,
  issue_order INT NOT NULL,
  object_type VARCHAR(128) NOT NULL,
  schema_name VARCHAR(255) NOT NULL,
  object_name VARCHAR(255) NOT NULL,
  error       TEXT NOT NULL,
  severity    VARCHAR(32) NOT NULL,
  PRIMARY KEY (run_id, issue_order)
);

CREATE TABLE IF NOT EXISTS schema_migrator.audit_events (
  id          uuid NOT NULL,
  actor       VARCHAR(255) NOT NULL,
  role        VARCHAR(128) NOT NULL,
  action      VARCHAR(128) NOT NULL,
  entity_type VARCHAR(128) NOT NULL,
  entity_id   VARCHAR(255) NOT NULL,
  target_id   uuid DEFAULT NULL,
  at          timestamptz NOT NULL,
  metadata    jsonb DEFAULT NULL,
  PRIMARY KEY (id, at)
) PARTITION BY RANGE (at);

CREATE TABLE IF NOT EXISTS schema_migrator.audit_events_default
  PARTITION OF schema_migrator.audit_events DEFAULT;

CREATE INDEX IF NOT EXISTS audit_events_at_idx ON schema_migrator.audit_events (at);
CREATE INDEX IF NOT EXISTS audit_events_actor_idx ON schema_migrator.audit_events (actor, at);
CREATE INDEX IF NOT EXISTS audit_events_entity_idx ON schema_migrator.audit_events (entity_type, entity_id, at);
CREATE INDEX IF NOT EXISTS audit_events_target_idx ON schema_migrator.audit_events (target_id, at);
-- object: schema_migrator_control_leases
-- depends_on: schema_migrator_run_state
-- Claims use conditional UPDATE plus affected-row checks. The fence increases
-- on every successful claim; stale owners cannot complete newer work.

CREATE TABLE IF NOT EXISTS schema_migrator.control_leases (
  resource_type    VARCHAR(64) NOT NULL,
  resource_id      VARCHAR(255) NOT NULL,
  owner_id         VARCHAR(128) DEFAULT NULL,
  lease_token      uuid DEFAULT NULL,
  fence            BIGINT NOT NULL DEFAULT 0,
  attempt_count    INT NOT NULL DEFAULT 0,
  lease_expires_at timestamptz DEFAULT NULL,
  next_attempt_at  timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error       TEXT DEFAULT NULL,
  updated_at       timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (resource_type, resource_id),
  CONSTRAINT control_leases_attempt_count_ck CHECK (attempt_count >= 0),
  CONSTRAINT control_leases_owner_token_ck CHECK (
    (owner_id IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
    OR
    (owner_id IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS control_leases_claim_idx ON schema_migrator.control_leases (resource_type, next_attempt_at, lease_expires_at);
