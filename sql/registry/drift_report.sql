-- schema-migrator customization registry report
--
-- Populated by:
--   sbt "run --db-kind postgres --sql-dir ./sql/postgres --customer <name> drift-check"
--
-- The registry stores the latest object-level drift status for each customer
-- overlay. Hash columns are SHA-256 values over normalized object DDL when the
-- live catalog exposes object-specific DDL; table/type presence checks may not
-- have a live_hash.

create schema if not exists schema_control;

create table if not exists schema_control.object_customization_registry (
  customer       text        not null,
  object_schema  text        not null,
  object_name    text        not null,
  object_type    text        not null,
  source_file    text,
  core_hash      text,
  live_hash      text,
  status         text        not null,
  drift_type     text,
  apply_status   text,
  expected_ddl   text,
  actual_ddl     text,
  last_checked   timestamptz not null default now(),
  primary key (customer, object_schema, object_type, object_name)
);

create index if not exists object_customization_registry_status_idx
  on schema_control.object_customization_registry(customer, status, drift_type);

comment on table schema_control.object_customization_registry is
  'Latest schema-migrator object drift status per customer overlay.';
comment on column schema_control.object_customization_registry.customer is
  'Customer overlay name supplied with --customer, or core when no overlay is selected.';
comment on column schema_control.object_customization_registry.core_hash is
  'SHA-256 hash of the normalized manifest DDL when object-specific DDL is available.';
comment on column schema_control.object_customization_registry.live_hash is
  'SHA-256 hash of the normalized live catalog DDL when object-specific DDL is available.';

create or replace view schema_control.drift_report as
select
  customer,
  object_schema,
  object_type,
  object_name,
  source_file,
  core_hash,
  live_hash,
  case
    when status = 'in_sync' then 'in_sync'
    when status = 'pending_migration' then 'pending_migration'
    when drift_type = 'definition_changed' then 'customized'
    when drift_type = 'missing_actual' then 'missing_actual'
    when drift_type = 'untracked_actual' then 'untracked_actual'
    when status = 'drift_detected' then 'drifted'
    else status
  end as customization_status,
  status as catalog_status,
  drift_type,
  apply_status,
  last_checked
from schema_control.object_customization_registry
order by customer, object_schema, object_type, object_name;

select *
from schema_control.drift_report
order by customer, object_schema, object_type, object_name;
