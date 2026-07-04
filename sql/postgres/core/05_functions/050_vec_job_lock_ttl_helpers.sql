-- object: vec_job_lock_ttl_helpers
-- folder: functions
-- depends_on: vec_job_locks

create or replace function vec_prune_stale_job_locks(
  p_lock_ttl interval default interval '5 minutes'
)
returns integer
language plpgsql
as $$
declare
  v_deleted integer := 0;
begin
  delete from vec_job_locks
   where locked_at < now() - coalesce(p_lock_ttl, interval '5 minutes');

  get diagnostics v_deleted = row_count;
  return v_deleted;
end;
$$;

create or replace function vec_try_begin_job(p_job_name text)
returns boolean
language plpgsql
as $$
begin
  if not pg_try_advisory_lock(hashtextextended(p_job_name, 0)) then
    raise notice '% already running, skipping', p_job_name;
    return false;
  end if;

  delete from vec_job_locks
   where job_name = p_job_name
     and locked_at < now() - interval '5 minutes';

  insert into vec_job_locks (job_name, locked_at, locked_by)
  values (p_job_name, now(), pg_backend_pid()::text)
  on conflict (job_name) do update
    set locked_at = excluded.locked_at,
        locked_by = excluded.locked_by;

  return true;
end;
$$;

create or replace function vec_try_begin_maintenance_job(p_job_name text)
returns boolean
language plpgsql
as $$
declare
  v_lock_name text := 'vec_maintenance_' || p_job_name;
begin
  if not pg_try_advisory_lock(hashtextextended(v_lock_name, 0)) then
    raise notice 'vector maintenance already running, skipping %', p_job_name;
    return false;
  end if;

  delete from vec_job_locks
   where job_name like 'maintenance:%'
     and locked_at < now() - interval '5 minutes';

  insert into vec_job_locks (job_name, locked_at, locked_by)
  values ('maintenance:' || p_job_name, now(), pg_backend_pid()::text)
  on conflict (job_name) do update
    set locked_at = excluded.locked_at,
        locked_by = excluded.locked_by;

  return true;
end;
$$;

create or replace function vec_finish_maintenance_job(p_job_name text)
returns void
language plpgsql
as $$
declare
  v_lock_name text := 'vec_maintenance_' || p_job_name;
begin
  delete from vec_job_locks where job_name = 'maintenance:' || p_job_name;
  perform pg_advisory_unlock(hashtextextended(v_lock_name, 0));
end;
$$;
