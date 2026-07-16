-- object: vec_embedding_job_leases
-- folder: tables
-- depends_on: vec_embedding_jobs
create table if not exists vec_embedding_job_leases (
  job_id bigint primary key references vec_embedding_jobs(job_id) on delete cascade,
  lease_token text,
  leased_at timestamptz,
  locked_by text,
  due_at timestamptz not null default now(),
  attempts integer not null default 0,
  max_attempts integer not null default 5,
  last_error text,
  completed_at timestamptz,
  constraint vec_embedding_job_leases_attempts_chk
    check (attempts >= 0 and max_attempts > 0)
);

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'vec_embedding_jobs'
      and column_name = 'lease_token'
  ) then
    execute $backfill$
      insert into vec_embedding_job_leases (
        job_id, lease_token, leased_at, locked_by, due_at,
        attempts, max_attempts, last_error, completed_at
      )
      select
        job_id, lease_token, leased_at, locked_by, due_at,
        attempts, max_attempts, last_error, completed_at
      from vec_embedding_jobs
      on conflict (job_id) do update set
        lease_token = excluded.lease_token,
        leased_at = excluded.leased_at,
        locked_by = excluded.locked_by,
        due_at = excluded.due_at,
        attempts = excluded.attempts,
        max_attempts = excluded.max_attempts,
        last_error = excluded.last_error,
        completed_at = excluded.completed_at
    $backfill$;
  else
    insert into vec_embedding_job_leases (job_id)
    select job_id from vec_embedding_jobs
    on conflict (job_id) do nothing;
  end if;
end;
$$;

create or replace function vec_embedding_jobs_ensure_lease()
returns trigger
language plpgsql
as $$
declare
  legacy jsonb := to_jsonb(new);
  previous jsonb := case when tg_op = 'UPDATE' then to_jsonb(old) else '{}'::jsonb end;
begin
  if pg_trigger_depth() > 1 then
    return new;
  end if;

  if tg_op = 'UPDATE'
     and not (
       legacy->'lease_token' is distinct from previous->'lease_token'
       or legacy->'leased_at' is distinct from previous->'leased_at'
       or legacy->'locked_by' is distinct from previous->'locked_by'
       or legacy->'due_at' is distinct from previous->'due_at'
       or legacy->'attempts' is distinct from previous->'attempts'
       or legacy->'max_attempts' is distinct from previous->'max_attempts'
       or legacy->'last_error' is distinct from previous->'last_error'
       or legacy->'completed_at' is distinct from previous->'completed_at'
     ) then
    return new;
  end if;

  insert into vec_embedding_job_leases (
    job_id, lease_token, leased_at, locked_by, due_at,
    attempts, max_attempts, last_error, completed_at
  )
  values (
    new.job_id,
    legacy->>'lease_token',
    (legacy->>'leased_at')::timestamptz,
    legacy->>'locked_by',
    coalesce((legacy->>'due_at')::timestamptz, now()),
    coalesce((legacy->>'attempts')::integer, 0),
    coalesce((legacy->>'max_attempts')::integer, 5),
    legacy->>'last_error',
    (legacy->>'completed_at')::timestamptz
  )
  on conflict (job_id) do update set
    lease_token = case when legacy ? 'lease_token' then excluded.lease_token else vec_embedding_job_leases.lease_token end,
    leased_at = case when legacy ? 'leased_at' then excluded.leased_at else vec_embedding_job_leases.leased_at end,
    locked_by = case when legacy ? 'locked_by' then excluded.locked_by else vec_embedding_job_leases.locked_by end,
    due_at = case when legacy ? 'due_at' then excluded.due_at else vec_embedding_job_leases.due_at end,
    attempts = case when legacy ? 'attempts' then excluded.attempts else vec_embedding_job_leases.attempts end,
    max_attempts = case when legacy ? 'max_attempts' then excluded.max_attempts else vec_embedding_job_leases.max_attempts end,
    last_error = case when legacy ? 'last_error' then excluded.last_error else vec_embedding_job_leases.last_error end,
    completed_at = case when legacy ? 'completed_at' then excluded.completed_at else vec_embedding_job_leases.completed_at end;
  return new;
end;
$$;

create or replace function vec_embedding_job_leases_to_legacy()
returns trigger
language plpgsql
as $$
begin
  if pg_trigger_depth() > 1 or not exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'vec_embedding_jobs'
      and column_name = 'lease_token'
  ) then
    return new;
  end if;

  execute $sync$
    update vec_embedding_jobs set
      lease_token = $1, leased_at = $2, locked_by = $3, due_at = $4,
      attempts = $5, max_attempts = $6, last_error = $7, completed_at = $8
    where job_id = $9
  $sync$ using
    new.lease_token, new.leased_at, new.locked_by, new.due_at,
    new.attempts, new.max_attempts, new.last_error, new.completed_at, new.job_id;
  return new;
end;
$$;

drop trigger if exists vec_embedding_jobs_ensure_lease on vec_embedding_jobs;
create trigger vec_embedding_jobs_ensure_lease
after insert or update on vec_embedding_jobs
for each row execute function vec_embedding_jobs_ensure_lease();

drop trigger if exists vec_embedding_job_leases_to_legacy on vec_embedding_job_leases;
create trigger vec_embedding_job_leases_to_legacy
after insert or update on vec_embedding_job_leases
for each row execute function vec_embedding_job_leases_to_legacy();
