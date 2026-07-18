create table targets (
  id uuid primary key,
  label text not null,
  app_name text not null,
  environment text not null,
  jdbc_url text not null,
  db_kind text not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  repo_url text not null,
  repo_branch text not null,
  repo_sql_path text not null,
  last_synced_commit text,
  last_synced_at timestamptz,
  password_ciphertext bytea,
  password_iv bytea,
  constraint target_password_complete check (
    (password_ciphertext is null and password_iv is null) or
    (password_ciphertext is not null and password_iv is not null)
  )
);
create index targets_created_at_idx on targets (created_at);

create table sql_files (
  target_id uuid not null,
  path text not null,
  folder text not null,
  filename text not null,
  content bytea not null,
  sha256 text not null,
  uploaded_at timestamptz not null,
  primary key (target_id, path)
);
create index sql_files_target_folder_filename_idx on sql_files (target_id, folder, filename);

create table patches (
  id uuid primary key,
  target_id uuid not null,
  version text not null,
  label text not null,
  status text not null,
  applied_at timestamptz,
  source_snapshot_id uuid
);
create index patches_target_version_idx on patches (target_id, version);
create index patches_status_idx on patches (status);

create table patch_scripts (
  id text primary key,
  patch_id uuid not null references patches(id) on delete cascade,
  script_order integer not null,
  filename text not null,
  checksum text not null,
  status text not null,
  error jsonb,
  duration_ms bigint,
  content bytea not null,
  unique (patch_id, script_order)
);

create table runs (
  id uuid primary key,
  target_id uuid not null,
  patch_id uuid not null,
  status text not null,
  started_at timestamptz not null,
  ended_at timestamptz,
  triggered_by text not null
);
create index runs_target_started_idx on runs (target_id, started_at);
create index runs_status_idx on runs (status);
create unique index runs_one_active_per_target_idx on runs (target_id)
where status in ('pending', 'running');

create table run_scripts (
  run_id uuid not null references runs(id) on delete cascade,
  script_id text not null,
  filename text not null,
  script_order integer not null,
  status text not null,
  error jsonb,
  duration_ms bigint,
  primary key (run_id, script_id),
  unique (run_id, script_order)
);

create table validations (
  run_id uuid primary key,
  target_id uuid not null,
  checked_at timestamptz not null,
  status text not null
);
create index validations_target_checked_idx on validations (target_id, checked_at);
create index validations_status_idx on validations (status);

create table validation_issues (
  run_id uuid not null references validations(run_id) on delete cascade,
  issue_order integer not null,
  object_type text not null,
  schema_name text not null,
  object_name text not null,
  error text not null,
  severity text not null,
  primary key (run_id, issue_order)
);

create table snapshots (
  id uuid primary key,
  target_id uuid not null,
  label text not null,
  created_at timestamptz not null,
  created_by text not null,
  file_count integer not null
);
create index snapshots_target_created_idx on snapshots (target_id, created_at desc);

create table snapshot_files (
  snapshot_id uuid not null references snapshots(id) on delete cascade,
  path text not null,
  folder text not null,
  filename text not null,
  sha256 text not null,
  content bytea not null,
  uploaded_at timestamptz not null,
  size_bytes bigint not null,
  primary key (snapshot_id, path)
);

create table audit_events (
  id uuid primary key,
  actor text not null,
  role text not null,
  action text not null,
  entity_type text not null,
  entity_id text not null,
  target_id uuid,
  at timestamptz not null,
  metadata jsonb
);
create index audit_events_at_idx on audit_events (at desc);
create index audit_events_actor_at_idx on audit_events (actor, at desc);
create index audit_events_entity_at_idx on audit_events (entity_type, entity_id, at desc);
create index audit_events_target_at_idx on audit_events (target_id, at desc);

create table keycloak_config (
  id text primary key,
  enabled boolean not null,
  issuer text,
  jwks_uri text,
  client_id text,
  audience text,
  updated_at timestamptz not null
);
