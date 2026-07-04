-- object: coordinator_backlog_retry_caps
-- folder: functions
-- depends_on: sync_backlog

create or replace function coordinator.fail_exhausted_backlog()
returns integer
language plpgsql
as $$
declare
  v_updated integer := 0;
begin
  update sync_backlog
     set status = 'failed',
         updated_at = now()
   where status = 'pending'
     and attempt_count >= max_attempts;

  get diagnostics v_updated = row_count;
  return v_updated;
end;
$$;

create or replace function coordinator.save_backlog_entry(p_payload jsonb)
returns void
language plpgsql
as $$
declare
  v_max_attempts integer := 5;
  v_attempt_count integer := 0;
begin
  if nullif(p_payload->>'dedupe_key', '') is null then
    raise exception 'coordinator.save_backlog_entry requires dedupe_key';
  end if;

  if coalesce(p_payload->>'max_attempts', '') ~ '^[0-9]+$' then
    v_max_attempts := greatest(coordinator.safe_int(p_payload->>'max_attempts'), 1);
  end if;

  if coalesce(p_payload->>'attempt_count', '') ~ '^[0-9]+$' then
    v_attempt_count := greatest(coordinator.safe_int(p_payload->>'attempt_count'), 0);
  end if;

  insert into sync_backlog (
    dedupe_key,
    stream_name,
    payload,
    failure_stage,
    status,
    attempt_count,
    max_attempts,
    last_error,
    created_at,
    updated_at
  )
  values (
    p_payload->>'dedupe_key',
    p_payload->>'stream_name',
    p_payload->'payload',
    coalesce(nullif(p_payload->>'failure_stage', ''), 'pre_publish'),
    case when v_attempt_count >= v_max_attempts then 'failed' else 'pending' end,
    v_attempt_count,
    v_max_attempts,
    nullif(p_payload->>'error', ''),
    now(),
    now()
  )
  on conflict (dedupe_key) do update
    set stream_name = excluded.stream_name,
        payload = excluded.payload,
        failure_stage = excluded.failure_stage,
        attempt_count = sync_backlog.attempt_count + 1,
        max_attempts = greatest(sync_backlog.max_attempts, excluded.max_attempts, 1),
        status = case
          when sync_backlog.attempt_count + 1 >= greatest(sync_backlog.max_attempts, excluded.max_attempts, 1)
          then 'failed'
          else 'pending'
        end,
        last_error = excluded.last_error,
        updated_at = now();
end;
$$;

create or replace function coordinator.list_pending_backlog()
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  perform coordinator.fail_exhausted_backlog();

  select coalesce(jsonb_agg(
    jsonb_build_object(
      'dedupe_key', dedupe_key,
      'stream_name', stream_name,
      'payload', payload,
      'failure_stage', failure_stage,
      'attempt_count', attempt_count,
      'max_attempts', max_attempts,
      'created_at', created_at
    ) order by created_at asc
  ), '[]'::jsonb)
    into v_result
  from (
    select dedupe_key, stream_name, payload, failure_stage, attempt_count, max_attempts, created_at
    from sync_backlog
    where status = 'pending'
      and attempt_count < max_attempts
    order by created_at asc
    limit 100
  ) pending;

  return v_result;
end;
$$;
