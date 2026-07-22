-- object: coordinator.save_backlog_entry
-- folder: functions
-- depends_on: sync_backlog
create or replace function coordinator.save_backlog_entry(p_payload jsonb)
returns void
language plpgsql
as $$
begin
  if nullif(p_payload->>'dedupe_key', '') is null then
    raise exception 'coordinator.save_backlog_entry requires dedupe_key';
  end if;

  insert into sync_backlog (
    dedupe_key,
    stream_name,
    payload,
    failure_stage,
    status,
    attempt_count,
    last_error,
    created_at,
    updated_at
  )
  values (
    p_payload->>'dedupe_key',
    p_payload->>'stream_name',
    p_payload->'payload',
    coalesce(nullif(p_payload->>'failure_stage', ''), 'pre_publish'),
    'pending',
    0,
    nullif(p_payload->>'error', ''),
    now(),
    now()
  )
  on conflict (dedupe_key) do update
    set stream_name = excluded.stream_name,
        payload = excluded.payload,
        failure_stage = excluded.failure_stage,
        status = 'pending',
        last_error = excluded.last_error,
        updated_at = now();
end;
$$;
