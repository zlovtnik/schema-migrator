package com.sslproxy.schema.db.postgres

import com.sslproxy.schema.db.DialectStatements

object PostgresStatements extends DialectStatements:
  val ensureSchemaControlSchemaSql: String =
    "create schema if not exists schema_control"

  val customizationRegistrySql: String =
    """
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
    """

  val bootstrapSql: String =
    s"""
    $ensureSchemaControlSchemaSql;

    create table if not exists schema_control.schema_objects (
      id              bigserial primary key,
      kind            text        not null,
      object_name     text        not null,
      source_file     text        not null,
      depends_on      text[]      not null default '{}',
      rollback_file   text,
      canonical_sql   text        not null,
      content_sha256  text        not null,
      applied_at      timestamptz,
      apply_status    text        not null default 'pending',
      last_error      text,
      created_at      timestamptz not null default now(),
      updated_at      timestamptz not null default now(),
      constraint schema_objects_unique unique (kind, object_name),
      constraint schema_objects_status_chk check (
        apply_status in ('pending', 'applied', 'failed', 'skipped', 'retired')
      )
    );

    alter table schema_control.schema_objects
      add column if not exists rollback_file text;

    alter table schema_control.schema_objects
      drop constraint if exists schema_objects_status_chk;

    alter table schema_control.schema_objects
      add constraint schema_objects_status_chk check (
        apply_status in ('pending', 'applied', 'failed', 'skipped', 'retired')
      );

    create table if not exists schema_control.schema_apply_log (
      log_id        bigserial primary key,
      kind          text        not null,
      object_name   text        not null,
      source_file   text        not null,
      action        text        not null,
      old_sha256    text,
      new_sha256    text,
      duration_ms   integer,
      error_text    text,
      applied_by    text,
      applied_at    timestamptz not null default now()
    );

    create index if not exists schema_apply_log_object_idx
      on schema_control.schema_apply_log(kind, object_name, applied_at desc);

    create or replace view schema_control.schema_ready as
    select
      now() as measured_at,
      count(*) filter (where apply_status <> 'retired')::bigint as total_count,
      count(*) filter (where apply_status = 'pending')::bigint as pending_count,
      count(*) filter (where apply_status = 'failed')::bigint as failed_count,
      count(*) filter (where apply_status in ('applied', 'skipped'))::bigint as applied_count,
      (
        count(*) filter (where apply_status <> 'retired') > 0
        and coalesce(bool_and(apply_status in ('applied', 'skipped', 'retired')), false)
      ) as all_applied,
      (
        count(*) filter (where apply_status <> 'retired') > 0
        and coalesce(bool_and(apply_status in ('applied', 'skipped', 'retired')), false)
        and count(*) filter (where apply_status = 'failed') = 0
      ) as ready,
      coalesce(
        array_agg(kind || ':' || object_name order by kind, object_name)
          filter (where apply_status = 'failed'),
        array[]::text[]
      ) as failed_objects,
      max(updated_at) as last_updated_at,
      max(applied_at) as last_applied_at
    from schema_control.schema_objects;

    $customizationRegistrySql
    """

  val prepareSql: String =
    """
    insert into schema_control.schema_objects (
      kind, object_name, source_file, depends_on, rollback_file, canonical_sql, content_sha256,
      applied_at, apply_status, last_error, updated_at
    ) values (
      ?, ?, ?, ?, ?, ?, ?,
      case when ?::text = 'pending' then null else now() end,
      ?, null, now()
    )
    on conflict (kind, object_name) do update set
      source_file = excluded.source_file,
      depends_on = excluded.depends_on,
      rollback_file = excluded.rollback_file,
      canonical_sql = excluded.canonical_sql,
      content_sha256 = excluded.content_sha256,
      applied_at = case
        when excluded.apply_status = 'pending' then null
        else coalesce(schema_control.schema_objects.applied_at, now())
      end,
      apply_status = excluded.apply_status,
      last_error = null,
      updated_at = now()
    """

  val lookupExistingSql: String =
    """
    select content_sha256, apply_status
      from schema_control.schema_objects
     where kind = ? and object_name = ?
    """

  val retireSql: String =
    """
    update schema_control.schema_objects
       set apply_status = 'retired',
           last_error = null,
           updated_at = now()
     where source_file = ?
       and apply_status <> 'retired'
    """

  val applyLogSql: String =
    """
    insert into schema_control.schema_apply_log (
      kind, object_name, source_file, action, old_sha256, new_sha256,
      duration_ms, error_text, applied_by
    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

  val updateStatusSql: String =
    """
    update schema_control.schema_objects
       set apply_status = ?,
           applied_at = ?,
           last_error = ?,
           updated_at = now()
     where kind = ? and object_name = ?
    """

  val updateStatusSkippedSql: String =
    """
    update schema_control.schema_objects
       set apply_status = 'skipped',
           last_error = null,
           updated_at = now()
     where kind = ? and object_name = ?
    """

  val rollbackStatusSql: String =
    """
    update schema_control.schema_objects
       set apply_status = 'pending',
           applied_at = null,
           last_error = null,
           updated_at = now()
     where kind = ? and object_name = ?
    """

  val fetchStatusSql: String =
    """
    select kind, object_name, source_file, apply_status,
           content_sha256,
           to_char(applied_at, 'YYYY-MM-DD HH24:MI:SS') as applied_at,
           last_error
      from schema_control.schema_objects
     order by kind, object_name
    """

  val fetchReadySql: String =
    """
    select total_count, pending_count, failed_count, applied_count, ready,
           failed_objects,
           to_char(last_updated_at, 'YYYY-MM-DD HH24:MI:SS') as last_updated_at,
           to_char(last_applied_at, 'YYYY-MM-DD HH24:MI:SS') as last_applied_at
      from schema_control.schema_ready
    """

  val fetchRollbackTargetSql: String =
    """
    select kind, object_name, source_file, content_sha256, rollback_file
      from schema_control.schema_objects
     where object_name = ?
     order by kind, object_name
    """

  val deleteCustomerRowsSql: String =
    "delete from schema_control.object_customization_registry where customer = ?"

  val insertCustomizationRegistrySql: String =
    """
    insert into schema_control.object_customization_registry (
      customer, object_schema, object_name, object_type, source_file,
      core_hash, live_hash, status, drift_type, apply_status,
      expected_ddl, actual_ddl, last_checked
    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    on conflict (customer, object_schema, object_type, object_name) do update set
      source_file = excluded.source_file,
      core_hash = excluded.core_hash,
      live_hash = excluded.live_hash,
      status = excluded.status,
      drift_type = excluded.drift_type,
      apply_status = excluded.apply_status,
      expected_ddl = excluded.expected_ddl,
      actual_ddl = excluded.actual_ddl,
      last_checked = excluded.last_checked
    """
