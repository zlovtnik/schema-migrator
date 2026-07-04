-- object: vec_build_frame_sequences
-- folder: functions
-- depends_on: vec_frame_sequences, wireless_frames
create or replace function vec_build_frame_sequences(
  p_from timestamptz default now() - interval '2 hours',
  p_to timestamptz default now()
)
returns integer
language plpgsql
as $$
declare
  v_count integer := 0;
begin
  if not vec_try_begin_job('vec_build_frame_sequences') then
    return 0;
  end if;

  with base as (
    select
      -- Prefer real session_key from payload; fall back to synthetic key
      -- derived from (source_mac, bssid, minute) so events with no session
      -- identifier still get grouped into meaningful frame sequences.
      coalesce(
        nullif(coalesce(session_key, payload->>'session_key'), ''),
        md5(
          coalesce(lower(nullif(coalesce(source_mac, payload->>'source_mac'), '')), '')
          || '|'
          || coalesce(lower(nullif(coalesce(bssid, payload->>'bssid', destination_bssid, payload->>'destination_bssid'), '')), '')
          || '|'
          || to_char(date_trunc('minute', observed_at), 'YYYY-MM-DD HH24:MI:SS')
        )
      ) as session_key,
      lower(nullif(coalesce(source_mac, payload->>'source_mac'), '')) as source_mac,
      nullif(coalesce(location_id, payload->>'location_id'), '') as location_id,
      nullif(coalesce(sensor_id, payload->>'sensor_id'), '') as sensor_id,
      observed_at,
      coalesce(
        nullif(frame_subtype, ''),
        nullif(payload->>'frame_subtype', '')
      ) as frame_subtype_value,
      case
        when upper(regexp_replace(coalesce(nullif(frame_subtype, ''), nullif(payload->>'frame_subtype', '')), '-', '_', 'g')) in
             ('PROBE_REQ', 'PROBE_REQUEST', 'PROBE_RESP', 'PROBE_RESPONSE') then 'DISCOVERY'
        when upper(regexp_replace(coalesce(nullif(frame_subtype, ''), nullif(payload->>'frame_subtype', '')), '-', '_', 'g')) in
             ('AUTH', 'AUTHENTICATION', 'ASSOC_REQ', 'ASSOCIATION_REQUEST', 'ASSOC_RESP', 'ASSOCIATION_RESPONSE',
              'REASSOC_REQ', 'REASSOCIATION_REQUEST', 'REASSOC_RESP', 'REASSOCIATION_RESPONSE') then 'ASSOCIATION'
        when upper(regexp_replace(coalesce(nullif(frame_subtype, ''), nullif(payload->>'frame_subtype', '')), '-', '_', 'g')) in
             ('DEAUTH', 'DEAUTHENTICATION', 'DISASSOC', 'DISASSOCIATION') then 'TERMINATION'
        when upper(regexp_replace(coalesce(nullif(frame_subtype, ''), nullif(payload->>'frame_subtype', '')), '-', '_', 'g')) in
             ('EAPOL', 'EAPOL_KEY') then 'HANDSHAKE'
        when upper(regexp_replace(coalesce(nullif(frame_subtype, ''), nullif(payload->>'frame_subtype', '')), '-', '_', 'g')) in
             ('DATA', 'DATA_QOS', 'QOS_DATA', 'NULL_DATA') then 'DATA'
        when upper(regexp_replace(coalesce(nullif(frame_subtype, ''), nullif(payload->>'frame_subtype', '')), '-', '_', 'g')) = 'BEACON' then 'BEACON'
        when upper(regexp_replace(coalesce(nullif(frame_subtype, ''), nullif(payload->>'frame_subtype', '')), '-', '_', 'g')) = 'ACTION' then 'ACTION'
        else 'OTHER'
      end as semantic_token
    from sync_events_expanded
    where stream_name = 'wireless.audit'
      and status = 'batched'
      and observed_at >= p_from
      and observed_at < p_to
      and coalesce(
        nullif(frame_subtype, ''),
        nullif(payload->>'frame_subtype', '')
      ) is not null
  ),
  prepared as (
    select
      session_key,
      min(source_mac) as source_mac,
      min(location_id) as location_id,
      min(sensor_id) as sensor_id,
      min(observed_at) as window_start,
      max(observed_at) as window_end,
      left(
        string_agg(
          upper(regexp_replace(frame_subtype_value, '-', '_', 'g')),
          ' ' order by observed_at
        ),
        65535
      ) as sequence_tokens,
      left(
        string_agg(semantic_token, ' ' order by observed_at),
        65535
      ) as semantic_tokens,
      count(*)::bigint as frame_count
    from base
    where session_key is not null
    group by session_key
  )
  insert into vec_frame_sequences (
    session_key,
    source_mac,
    location_id,
    sensor_id,
    window_start,
    window_end,
    sequence_tokens,
    semantic_tokens,
    frame_count,
    created_at,
    updated_at
  )
  select
    session_key,
    source_mac,
    location_id,
    sensor_id,
    window_start,
    window_end,
    sequence_tokens,
    semantic_tokens,
    frame_count,
    now(),
    now()
  from prepared
  on conflict (session_key) do update set
    source_mac = excluded.source_mac,
    location_id = excluded.location_id,
    sensor_id = excluded.sensor_id,
    window_start = excluded.window_start,
    window_end = excluded.window_end,
    sequence_tokens = excluded.sequence_tokens,
    semantic_tokens = excluded.semantic_tokens,
    frame_count = excluded.frame_count,
    updated_at = now();

  get diagnostics v_count = row_count;
  perform vec_finish_job('vec_build_frame_sequences');
  return v_count;
exception when others then
  perform vec_finish_job('vec_build_frame_sequences');
  raise;
end;
$$;
