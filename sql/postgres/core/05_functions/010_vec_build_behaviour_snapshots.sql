-- object: vec_build_behaviour_snapshots
-- folder: functions
-- depends_on: vec_behaviour_snapshots
create or replace function vec_build_behaviour_snapshots(
  p_from timestamptz default now() - interval '2 hours',
  p_to timestamptz default now(),
  p_window interval default interval '15 minutes'
)
returns integer
language plpgsql
as $$
declare
  v_count integer := 0;
begin
  if not vec_try_begin_job('vec_build_behaviour_snapshots') then
    return 0;
  end if;

  with base as (
    select
      lower(nullif(coalesce(source_mac, payload->>'source_mac'), '')) as source_mac,
      nullif(coalesce(location_id, payload->>'location_id'), '') as location_id,
      nullif(coalesce(sensor_id, payload->>'sensor_id'), '') as sensor_id,
      date_bin(p_window, observed_at, timestamptz '2000-01-01 00:00:00+00') as window_start,
      coalesce(app_protocol, payload->>'app_protocol', payload->>'ip_protocol_name', payload->>'transport_protocol', 'unknown') as app_protocol,
      coalesce(frame_type, payload->>'frame_type', payload->>'frame_subtype', 'unknown') as frame_type,
      coalesce(
        signal_dbm,
        case when payload->>'signal_dbm' ~ '^-?[0-9]+$' then (payload->>'signal_dbm')::integer end
      ) as signal_dbm,
      coalesce(channel_number::text, payload->>'channel_number', payload->>'channel') as channel_number,
      coalesce(retry, false) as retry,
      coalesce(protected, false) as protected,
      coalesce(bssid, payload->>'bssid', destination_bssid, payload->>'destination_bssid') as bssid,
      coalesce(wps_device_name, payload->>'wps_device_name') as wps_device_name,
      coalesce(wps_manufacturer, payload->>'wps_manufacturer') as wps_manufacturer,
      coalesce(wps_model_name, payload->>'wps_model_name') as wps_model_name,
      coalesce(device_fingerprint, payload->>'device_fingerprint') as device_fingerprint
    from sync_events_expanded
    where stream_name = 'wireless.audit'
      and status = 'batched'
      and observed_at >= p_from
      and observed_at < p_to
      and nullif(coalesce(source_mac, payload->>'source_mac'), '') is not null
  ),
  rollup as (
    select
      source_mac,
      location_id,
      min(sensor_id) filter (where sensor_id is not null) as sensor_id,
      window_start,
      window_start + p_window as window_end,
      count(*)::bigint as event_count,
      min(signal_dbm) as signal_min_dbm,
      max(signal_dbm) as signal_max_dbm,
      round(avg(signal_dbm)::numeric, 2) as signal_avg_dbm,
      count(*) filter (where retry)::bigint as retry_count,
      count(*) filter (where protected)::bigint as protected_count,
      count(*) filter (where not protected)::bigint as unprotected_count,
      count(distinct lower(bssid)) filter (where bssid is not null)::bigint as unique_bssid_count,
      bool_or(wps_device_name is not null or wps_manufacturer is not null or wps_model_name is not null) as has_wps_identity,
      count(distinct device_fingerprint) filter (where device_fingerprint is not null)::bigint as device_fingerprint_count
    from base
    group by source_mac, location_id, window_start
  ),
  protocol_counts as (
    select source_mac, location_id, window_start, app_protocol, count(*)::bigint as item_count
    from base
    group by source_mac, location_id, window_start, app_protocol
  ),
  protocol_json as (
    select source_mac, location_id, window_start, jsonb_object_agg(app_protocol, item_count order by app_protocol) as protocol_mix
    from protocol_counts
    group by source_mac, location_id, window_start
  ),
  frame_counts as (
    select source_mac, location_id, window_start, frame_type, count(*)::bigint as item_count
    from base
    group by source_mac, location_id, window_start, frame_type
  ),
  frame_json as (
    select source_mac, location_id, window_start, jsonb_object_agg(frame_type, item_count order by frame_type) as frame_type_distribution
    from frame_counts
    group by source_mac, location_id, window_start
  ),
  channel_counts as (
    select source_mac, location_id, window_start, channel_number, count(*)::bigint as item_count
    from base
    where channel_number is not null
    group by source_mac, location_id, window_start, channel_number
  ),
  channel_mix as (
    select
      source_mac,
      location_id,
      window_start,
      string_agg(channel_number || ':' || item_count::text, ' ' order by item_count desc, channel_number) as channel_mix
    from channel_counts
    group by source_mac, location_id, window_start
  ),
  cross_mac as (
    select
      location_id,
      window_start,
      bool_and(
        (get_byte(decode(split_part(source_mac, ':', 1), 'hex'), 0) & 2) = 2
      ) as is_locally_administered,
      count(distinct source_mac) filter (
        where (get_byte(decode(split_part(source_mac, ':', 1), 'hex'), 0) & 2) = 2
      )::bigint as la_mac_count,
      max(signal_dbm) - min(signal_dbm) as signal_range_dbm
    from base
    where signal_dbm is not null
      and source_mac ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'
    group by location_id, window_start
  ),
  prepared as (
    select
      md5(r.source_mac || '|' || coalesce(r.location_id, '') || '|' || r.window_start::text || '|' || r.window_end::text) as snapshot_key,
      r.source_mac,
      r.location_id,
      r.sensor_id,
      r.window_start,
      r.window_end,
      r.event_count,
      coalesce(p.protocol_mix, '{}'::jsonb) as protocol_mix,
      coalesce(f.frame_type_distribution, '{}'::jsonb) as frame_type_distribution,
      r.signal_min_dbm,
      r.signal_max_dbm,
      r.signal_avg_dbm,
      r.retry_count,
      r.protected_count,
      r.unprotected_count,
      r.unique_bssid_count,
      jsonb_build_object(
        'has_wps_identity', coalesce(r.has_wps_identity, false),
        'device_fingerprint_count', r.device_fingerprint_count,
        'unique_bssid_count', r.unique_bssid_count,
        'protected_ratio', case when r.event_count = 0 then 0 else round((r.protected_count::numeric / r.event_count::numeric), 4) end,
        'retry_ratio', case when r.event_count = 0 then 0 else round((r.retry_count::numeric / r.event_count::numeric), 4) end,
        'is_locally_administered', coalesce(cm.is_locally_administered, false),
        'la_mac_count_in_window', coalesce(cm.la_mac_count, 0),
        'signal_range_dbm', coalesce(cm.signal_range_dbm, 0),
        'rotation_confidence', case
          when coalesce(cm.la_mac_count, 0) >= 3
           and coalesce(cm.signal_range_dbm, 999) < 20
          then least(1.0, round(coalesce(cm.la_mac_count, 0)::numeric / 5.0, 2))
          else 0
        end
      ) as mac_rotation_indicators,
      concat_ws(
        E'\n',
        'kind: behaviour_window',
        'source_mac: ' || r.source_mac,
        'location_id: ' || coalesce(r.location_id, 'unknown'),
        'sensor_id: ' || coalesce(r.sensor_id, 'unknown'),
        'window_start: ' || r.window_start::text,
        'window_end: ' || r.window_end::text,
        'event_count: ' || r.event_count::text,
        'protocol_mix: ' || coalesce(p.protocol_mix, '{}'::jsonb)::text,
        'frame_type_distribution: ' || coalesce(f.frame_type_distribution, '{}'::jsonb)::text,
        'signal_min_dbm: ' || coalesce(r.signal_min_dbm::text, 'unknown'),
        'signal_max_dbm: ' || coalesce(r.signal_max_dbm::text, 'unknown'),
        'signal_avg_dbm: ' || coalesce(r.signal_avg_dbm::text, 'unknown'),
        'retry_count: ' || r.retry_count::text,
        'protected_count: ' || r.protected_count::text,
        'unprotected_count: ' || r.unprotected_count::text,
        'unique_bssid_count: ' || r.unique_bssid_count::text,
        'rf_channel_mix: ' || coalesce(ch.channel_mix, 'unknown'),
        'rf_bssid_count: ' || r.unique_bssid_count::text,
        'la_mac_count_in_window: ' || coalesce(cm.la_mac_count, 0)::text,
        'is_locally_administered: ' || coalesce(cm.is_locally_administered, false)::text,
        'signal_range_dbm: ' || coalesce(cm.signal_range_dbm::text, 'unknown'),
        'rotation_confidence: ' || coalesce(
          case
            when coalesce(cm.la_mac_count, 0) >= 3
             and coalesce(cm.signal_range_dbm, 999) < 20
            then least(1.0, round(coalesce(cm.la_mac_count, 0)::numeric / 5.0, 2))
            else 0
          end,
          0
        )::text
      ) as text_summary,
      -- Identity-stripped text for dense embedding: behavioural signal only
      concat_ws(
        E'\n',
        'kind: behaviour_window',
        'window_start: ' || r.window_start::text,
        'window_end: ' || r.window_end::text,
        'event_count: ' || r.event_count::text,
        'protocol_mix: ' || coalesce(p.protocol_mix, '{}'::jsonb)::text,
        'frame_type_distribution: ' || coalesce(f.frame_type_distribution, '{}'::jsonb)::text,
        'signal_min_dbm: ' || coalesce(r.signal_min_dbm::text, 'unknown'),
        'signal_max_dbm: ' || coalesce(r.signal_max_dbm::text, 'unknown'),
        'signal_avg_dbm: ' || coalesce(r.signal_avg_dbm::text, 'unknown'),
        'retry_count: ' || r.retry_count::text,
        'protected_count: ' || r.protected_count::text,
        'unprotected_count: ' || r.unprotected_count::text,
        'unique_bssid_count: ' || r.unique_bssid_count::text,
        'rf_channel_mix: ' || coalesce(ch.channel_mix, 'unknown'),
        'rf_bssid_count: ' || r.unique_bssid_count::text,
        'la_mac_count_in_window: ' || coalesce(cm.la_mac_count, 0)::text,
        'is_locally_administered: ' || coalesce(cm.is_locally_administered, false)::text,
        'signal_range_dbm: ' || coalesce(cm.signal_range_dbm::text, 'unknown'),
        'rotation_confidence: ' || coalesce(
          case
            when coalesce(cm.la_mac_count, 0) >= 3
             and coalesce(cm.signal_range_dbm, 999) < 20
            then least(1.0, round(coalesce(cm.la_mac_count, 0)::numeric / 5.0, 2))
            else 0
          end,
          0
        )::text
      ) as embedding_text
    from rollup r
    left join protocol_json p
      on p.source_mac = r.source_mac
     and p.location_id is not distinct from r.location_id
     and p.window_start = r.window_start
    left join frame_json f
      on f.source_mac = r.source_mac
     and f.location_id is not distinct from r.location_id
     and f.window_start = r.window_start
    left join channel_mix ch
      on ch.source_mac = r.source_mac
     and ch.location_id is not distinct from r.location_id
     and ch.window_start = r.window_start
    left join cross_mac cm
      on cm.location_id is not distinct from r.location_id
     and cm.window_start = r.window_start
  )
  insert into vec_behaviour_snapshots (
    snapshot_key, source_mac, location_id, sensor_id, window_start, window_end,
    event_count, protocol_mix, frame_type_distribution, signal_min_dbm, signal_max_dbm,
    signal_avg_dbm, retry_count, protected_count, unprotected_count, unique_bssid_count,
    mac_rotation_indicators, text_summary, embedding_text, created_at, updated_at
  )
  select
    snapshot_key, source_mac, location_id, sensor_id, window_start, window_end,
    event_count, protocol_mix, frame_type_distribution, signal_min_dbm, signal_max_dbm,
    signal_avg_dbm, retry_count, protected_count, unprotected_count, unique_bssid_count,
    mac_rotation_indicators, text_summary, embedding_text, now(), now()
  from prepared
  on conflict (snapshot_key) do update set
    sensor_id = excluded.sensor_id,
    event_count = excluded.event_count,
    protocol_mix = excluded.protocol_mix,
    frame_type_distribution = excluded.frame_type_distribution,
    signal_min_dbm = excluded.signal_min_dbm,
    signal_max_dbm = excluded.signal_max_dbm,
    signal_avg_dbm = excluded.signal_avg_dbm,
    retry_count = excluded.retry_count,
    protected_count = excluded.protected_count,
    unprotected_count = excluded.unprotected_count,
    unique_bssid_count = excluded.unique_bssid_count,
    mac_rotation_indicators = excluded.mac_rotation_indicators,
    text_summary = excluded.text_summary,
    embedding_text = excluded.embedding_text,
    updated_at = now();

  get diagnostics v_count = row_count;
  perform vec_finish_job('vec_build_behaviour_snapshots');
  return v_count;
exception when others then
  perform vec_finish_job('vec_build_behaviour_snapshots');
  raise;
end;
$$;
