package com.sslproxy.schema.db.tidb

import com.sslproxy.schema.db.DialectStatements

object TiDBStatements extends DialectStatements:
  val bootstrapSql: String =
    """
    create table if not exists schema_control.schema_objects (
      id              bigint       not null auto_increment,
      kind            varchar(64)  not null,
      object_name     varchar(256) not null,
      source_file     varchar(512) not null,
      depends_on      text,
      rollback_file   varchar(512) default null,
      canonical_sql   longtext     not null,
      content_sha256  varchar(64)  not null,
      applied_at      datetime(6)  default null,
      apply_status    varchar(16)  not null default 'pending',
      last_error      text,
      created_at      datetime(6)  not null default current_timestamp(6),
      updated_at      datetime(6)  not null default current_timestamp(6) on update current_timestamp(6),
      primary key (id),
      unique key schema_objects_unique (kind, object_name),
      constraint schema_objects_status_chk check (
        apply_status in ('pending', 'applied', 'failed', 'skipped', 'retired')
      )
    ) engine=innodb default charset=utf8mb4 collate=utf8mb4_unicode_ci;

    create table if not exists schema_control.schema_apply_log (
      log_id        bigint       not null auto_increment,
      kind          varchar(64)  not null,
      object_name   varchar(256) not null,
      source_file   varchar(512) not null,
      action        varchar(32)  not null,
      old_sha256    varchar(64)  default null,
      new_sha256    varchar(64)  default null,
      duration_ms   int          default null,
      error_text    longtext,
      applied_by    varchar(128) default null,
      applied_at    datetime(6)  not null default current_timestamp(6),
      primary key (log_id),
      key schema_apply_log_object_idx (kind, object_name, applied_at desc)
    ) engine=innodb default charset=utf8mb4 collate=utf8mb4_unicode_ci;

    create or replace view schema_control.schema_ready as
    select
      current_timestamp(6) as measured_at,
      coalesce(sum(case when apply_status <> 'retired' then 1 else 0 end), 0) as total_count,
      coalesce(sum(case when apply_status = 'pending' then 1 else 0 end), 0) as pending_count,
      coalesce(sum(case when apply_status = 'failed' then 1 else 0 end), 0) as failed_count,
      coalesce(sum(case when apply_status in ('applied', 'skipped') then 1 else 0 end), 0) as applied_count,
      case
        when coalesce(sum(case when apply_status <> 'retired' then 1 else 0 end), 0) > 0
         and coalesce(sum(case when apply_status not in ('applied', 'skipped', 'retired') then 1 else 0 end), 0) = 0
         and coalesce(sum(case when apply_status = 'failed' then 1 else 0 end), 0) = 0
        then '1' else '0'
      end as ready,
      coalesce(
        group_concat(case when apply_status = 'failed' then concat(kind, ':', object_name) end
          order by kind, object_name separator ','),
        ''
      ) as failed_objects,
      max(updated_at) as last_updated_at,
      max(applied_at) as last_applied_at
    from schema_control.schema_objects;
    """

  val prepareSql: String =
    """
    insert into schema_control.schema_objects (
      kind, object_name, source_file, depends_on, rollback_file,
      canonical_sql, content_sha256,
      applied_at, apply_status, last_error, updated_at
    ) values (
      ?, ?, ?, ?, ?,
      ?, ?,
      case when ? = 'pending' then null else current_timestamp(6) end,
      ?, null, current_timestamp(6)
    )
    on duplicate key update
      source_file     = values(source_file),
      depends_on      = values(depends_on),
      rollback_file   = values(rollback_file),
      canonical_sql   = values(canonical_sql),
      content_sha256  = values(content_sha256),
      applied_at      = case
        when values(apply_status) = 'pending' then null
        else coalesce(schema_control.schema_objects.applied_at, current_timestamp(6))
      end,
      apply_status    = values(apply_status),
      last_error      = null,
      updated_at      = current_timestamp(6)
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
           updated_at = current_timestamp(6)
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
           applied_at = case when ? = 'applied' then current_timestamp(6) else null end,
           last_error = ?,
           updated_at = current_timestamp(6)
     where kind = ? and object_name = ?
    """

  val updateStatusSkippedSql: String =
    """
    update schema_control.schema_objects
       set apply_status = 'skipped',
           last_error = null,
           updated_at = current_timestamp(6)
     where kind = ? and object_name = ?
    """

  val rollbackStatusSql: String =
    """
    update schema_control.schema_objects
       set apply_status = 'pending',
           applied_at = null,
           last_error = null,
           updated_at = current_timestamp(6)
     where kind = ? and object_name = ?
    """

  val fetchStatusSql: String =
    """
    select kind, object_name, source_file, apply_status,
           content_sha256,
           date_format(applied_at, '%Y-%m-%d %H:%i:%s') as applied_at,
           last_error
      from schema_control.schema_objects
     order by kind, object_name
    """

  val fetchReadySql: String =
    """
    select total_count, pending_count, failed_count, applied_count, ready,
           failed_objects,
           date_format(last_updated_at, '%Y-%m-%d %H:%i:%s') as last_updated_at,
           date_format(last_applied_at, '%Y-%m-%d %H:%i:%s') as last_applied_at
      from schema_control.schema_ready
    """

  val fetchRollbackTargetSql: String =
    """
    select kind, object_name, source_file, content_sha256, rollback_file
      from schema_control.schema_objects
     where object_name = ?
     order by kind, object_name
    """

  val lockAcquireSql: String =
    "select get_lock('schema_migrate', 10)"

  val lockReleaseSql: String =
    "select release_lock('schema_migrate')"
