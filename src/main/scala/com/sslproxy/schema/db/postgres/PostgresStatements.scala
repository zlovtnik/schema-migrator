package com.sslproxy.schema.db.postgres

object PostgresStatements:
  val bootstrapSql: String =
    """
    create schema if not exists schema_control;

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
        apply_status in ('pending', 'applied', 'failed', 'skipped')
      )
    );

    alter table schema_control.schema_objects
      add column if not exists rollback_file text;

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
      count(*)::bigint as total_count,
      count(*) filter (where apply_status = 'pending')::bigint as pending_count,
      count(*) filter (where apply_status = 'failed')::bigint as failed_count,
      count(*) filter (where apply_status in ('applied', 'skipped'))::bigint as applied_count,
      (
        count(*) > 0
        and coalesce(bool_and(apply_status in ('applied', 'skipped')), false)
      ) as all_applied,
      (
        count(*) > 0
        and coalesce(bool_and(apply_status in ('applied', 'skipped')), false)
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
