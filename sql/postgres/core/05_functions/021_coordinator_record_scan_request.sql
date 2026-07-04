-- object: coordinator.record_scan_request
-- folder: functions
-- depends_on: sync_events, wireless_frames
create or replace function coordinator.record_scan_request(
  p_request jsonb,
  p_payload jsonb,
  p_payload_sha256 text,
  p_stream_names text[]
)
returns jsonb
language plpgsql
as $$
declare
  v_stream_name text := p_request->>'stream_name';
  v_dedupe_key text := p_request->>'dedupe_key';
  v_payload_ref text := p_request->>'payload_ref';
  v_observed_at timestamptz := (p_request->>'observed_at')::timestamptz;
  v_recorded boolean := false;
begin
  if v_stream_name is null or not exists (
    select 1
      from unnest(p_stream_names) as configured(stream_name)
     where btrim(configured.stream_name) = v_stream_name
  ) then
    return jsonb_build_object(
      'recorded', false,
      'reason', 'unsupported_stream',
      'stream_name', v_stream_name
    );
  end if;

  if nullif(v_dedupe_key, '') is null then
    raise exception 'scan request missing dedupe_key';
  end if;
  if nullif(v_payload_ref, '') is null then
    raise exception 'scan request missing payload_ref';
  end if;

  if exists (
    select 1
      from sync_event_tombstones tombstone
     where tombstone.dedupe_key = v_dedupe_key
       and tombstone.stream_name = v_stream_name
       and tombstone.expires_at > now()
  ) then
    return jsonb_build_object(
      'recorded', false,
      'reason', 'tombstone_dedupe',
      'dedupe_key', v_dedupe_key,
      'stream_name', v_stream_name
    );
  end if;

  insert into sync_events (
    dedupe_key,
    stream_name,
    observed_at,
    payload_ref,
    payload,
    payload_sha256,
    status,
    attempt_count,
    last_error,
    producer,
    event_kind,
    created_at,
    updated_at
  )
  values (
    v_dedupe_key,
    v_stream_name,
    v_observed_at,
    v_payload_ref,
    p_payload,
    p_payload_sha256,
    'pending',
    0,
    null,
    'ssl-proxy',
    nullif(p_payload->>'type', ''),
    now(),
    now()
  )
  on conflict (dedupe_key)
  do update set
    observed_at = excluded.observed_at,
    payload_ref = excluded.payload_ref,
    payload = coalesce(excluded.payload, sync_events.payload),
    payload_sha256 = excluded.payload_sha256,
    producer = excluded.producer,
    event_kind = coalesce(excluded.event_kind, sync_events.event_kind),
    status = case
      when sync_events.status in ('pending', 'failed') then 'pending'
      else sync_events.status
    end,
    last_error = case
      when sync_events.status in ('pending', 'failed') then null
      else sync_events.last_error
    end,
    updated_at = now()
  returning true into v_recorded;

  perform coordinator.upsert_wireless_frame_from_payload(v_dedupe_key, v_stream_name, p_payload);

  return jsonb_build_object(
    'recorded', coalesce(v_recorded, false),
    'dedupe_key', v_dedupe_key,
    'stream_name', v_stream_name
  );
end;
$$;
