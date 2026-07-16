-- object: coordinator.record_scan_request_batch tombstone-safe wireless frame upsert
-- folder: functions
-- depends_on: sync_events, sync_event_tombstones, wireless_frames
create or replace function coordinator.record_scan_request_batch(
  p_requests jsonb[],
  p_payloads jsonb[],
  p_payload_sha256s text[],
  p_stream_names text[]
)
returns integer
language plpgsql
as $$
declare
  v_recorded_count integer := 0;
begin
  if cardinality(p_requests) <> cardinality(p_payloads)
     or cardinality(p_requests) <> cardinality(p_payload_sha256s) then
    raise exception 'record_scan_request_batch array length mismatch';
  end if;

  if exists (
    with incoming as (
      select raw.request->>'stream_name' as stream_name,
             raw.request->>'dedupe_key' as dedupe_key
        from unnest(p_requests, p_payloads, p_payload_sha256s) as raw(request, payload, payload_sha256)
    ),
    configured_streams as (
      select btrim(configured.stream_name) as stream_name
        from unnest(p_stream_names) as configured(stream_name)
       where btrim(configured.stream_name) <> ''
    )
    select 1
      from incoming
      join configured_streams on configured_streams.stream_name = incoming.stream_name
     where nullif(dedupe_key, '') is null
  ) then
    raise exception 'scan request missing dedupe_key';
  end if;

  if exists (
    with incoming as (
      select raw.request->>'stream_name' as stream_name,
             raw.request->>'payload_ref' as payload_ref
        from unnest(p_requests, p_payloads, p_payload_sha256s) as raw(request, payload, payload_sha256)
    ),
    configured_streams as (
      select btrim(configured.stream_name) as stream_name
        from unnest(p_stream_names) as configured(stream_name)
       where btrim(configured.stream_name) <> ''
    )
    select 1
      from incoming
      join configured_streams on configured_streams.stream_name = incoming.stream_name
     where nullif(payload_ref, '') is null
  ) then
    raise exception 'scan request missing payload_ref';
  end if;

  with incoming as (
    select raw.request,
           raw.payload,
           raw.payload_sha256,
           raw.request->>'stream_name' as stream_name,
           raw.request->>'dedupe_key' as dedupe_key,
           raw.request->>'payload_ref' as payload_ref,
           raw.request->>'observed_at' as observed_at_text
      from unnest(p_requests, p_payloads, p_payload_sha256s) as raw(request, payload, payload_sha256)
  ),
  configured_streams as (
    select btrim(configured.stream_name) as stream_name
      from unnest(p_stream_names) as configured(stream_name)
     where btrim(configured.stream_name) <> ''
  ),
  typed as (
    select incoming.*,
           coordinator.safe_timestamptz(incoming.observed_at_text) as observed_at
      from incoming
  ),
  valid as (
    select typed.*
      from typed
      join configured_streams on configured_streams.stream_name = typed.stream_name
      left join sync_event_tombstones tombstone
        on tombstone.dedupe_key = typed.dedupe_key
       and tombstone.stream_name = typed.stream_name
       and tombstone.expires_at > now()
     where tombstone.dedupe_key is null
       and typed.observed_at is not null
  ),
  upserted as (
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
    select dedupe_key,
           stream_name,
           observed_at,
           payload_ref,
           payload,
           payload_sha256,
           'pending',
           0,
           null,
           'ssl-proxy',
           nullif(payload->>'type', ''),
           now(),
           now()
      from valid
    on conflict (dedupe_key, stream_name)
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
    returning 1
  )
  select count(*) into v_recorded_count from upserted;

  perform coordinator.upsert_wireless_frame_from_payload(
    raw.dedupe_key,
    raw.stream_name,
    raw.payload
  )
  from (
    select raw.request,
           raw.payload,
           raw.request->>'stream_name' as stream_name,
           raw.request->>'dedupe_key' as dedupe_key
      from unnest(p_requests, p_payloads, p_payload_sha256s) as raw(request, payload, payload_sha256)
  ) raw
  join unnest(p_stream_names) as configured(stream_name)
    on btrim(configured.stream_name) = raw.stream_name
  left join sync_event_tombstones tombstone
    on tombstone.dedupe_key = raw.dedupe_key
   and tombstone.stream_name = raw.stream_name
   and tombstone.expires_at > now()
  where raw.stream_name = 'wireless.audit'
    and tombstone.dedupe_key is null
    and coordinator.safe_timestamptz(raw.request->>'observed_at') is not null;

  return coalesce(v_recorded_count, 0);
end;
$$;
