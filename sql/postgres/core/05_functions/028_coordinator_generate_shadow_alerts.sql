-- object: coordinator.generate_shadow_alerts
-- folder: functions
-- depends_on: sync_events, wireless_frames, wireless_shadow_alerts
create or replace function coordinator.generate_shadow_alerts()
returns setof jsonb
language sql
as $$
  with wireless as (
    select
      event.observed_at,
      lower(frame.source_mac) as source_mac,
      lower(coalesce(nullif(trim(frame.destination_bssid), ''), nullif(trim(frame.bssid), ''))) as destination_bssid,
      frame.ssid,
      radio.signal_dbm,
      coalesce(frame.sensor_id, event.payload->>'sensor_id') as sensor_id,
      coalesce(frame.location_id, event.payload->>'location_id') as location_id
    from sync_events event
    join wireless_frames frame on frame.dedupe_key = event.dedupe_key
    join wireless_frame_radio radio on radio.dedupe_key = frame.dedupe_key
    where event.stream_name = 'wireless.audit'
      and event.observed_at >= now() - interval '60 seconds'
      and frame.source_mac is not null
      and lower(frame.source_mac) ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'
      and radio.signal_dbm >= -50
  ),
  candidates as (
    select distinct on (source_mac)
      observed_at,
      source_mac,
      destination_bssid,
      ssid,
      sensor_id,
      location_id,
      signal_dbm,
      'strong_wireless_without_proxy_presence'::text as reason,
      jsonb_build_object(
        'window_seconds', 60,
        'signal_threshold_dbm', -50,
        'presence_window_seconds', 300
      ) as evidence
    from wireless w
    where not exists (
      select 1
        from wireless_authorized_networks awn
       where awn.enabled
         and (awn.location_id is null or awn.location_id = w.location_id)
         and (awn.ssid is null or (w.ssid is not null and lower(awn.ssid) = lower(w.ssid)))
         and (awn.bssid is null or (w.destination_bssid is not null and lower(awn.bssid) = w.destination_bssid))
         and (awn.ssid is not null or awn.bssid is not null)
    )
      and not exists (
        select 1
          from devices d
         where d.mac_id = w.source_mac
           and d.last_seen >= now() - interval '5 minutes'
      )
      and not exists (
        select 1
          from sync_events proxy
          join devices d on d.mac_id = lower(coalesce(proxy.payload->>'mac_id', proxy.payload->>'device_id'))
         where proxy.stream_name = 'proxy.events'
           and proxy.observed_at >= now() - interval '5 minutes'
           and d.mac_id = w.source_mac
      )
    order by source_mac, observed_at desc
  ),
  inserted as (
    insert into wireless_shadow_alerts as target (
      source_mac,
      first_occurred_at,
      last_occurred_at,
      occurrence_count,
      destination_bssid,
      ssid,
      sensor_id,
      location_id,
      signal_dbm,
      reason,
      evidence,
      created_at,
      updated_at
    )
    select
      source_mac,
      observed_at,
      observed_at,
      1,
      destination_bssid,
      ssid,
      sensor_id,
      location_id,
      signal_dbm,
      reason,
      evidence,
      now(),
      now()
    from candidates
    on conflict (source_mac) do update
      set last_occurred_at = greatest(target.last_occurred_at, excluded.last_occurred_at),
          occurrence_count = target.occurrence_count + 1,
          destination_bssid = case when excluded.last_occurred_at >= target.last_occurred_at then excluded.destination_bssid else target.destination_bssid end,
          ssid = case when excluded.last_occurred_at >= target.last_occurred_at then excluded.ssid else target.ssid end,
          sensor_id = case when excluded.last_occurred_at >= target.last_occurred_at then excluded.sensor_id else target.sensor_id end,
          location_id = case when excluded.last_occurred_at >= target.last_occurred_at then excluded.location_id else target.location_id end,
          signal_dbm = case when excluded.last_occurred_at >= target.last_occurred_at then excluded.signal_dbm else target.signal_dbm end,
          reason = excluded.reason,
          evidence = excluded.evidence,
          resolved_at = null,
          updated_at = now()
    returning *
  )
  select jsonb_build_object(
           'event_type', 'shadow_device',
           'first_occurred_at', first_occurred_at,
           'last_occurred_at', last_occurred_at,
           'source_mac', source_mac,
           'occurrence_count', occurrence_count,
           'destination_bssid', destination_bssid,
           'ssid', ssid,
           'sensor_id', sensor_id,
           'location_id', location_id,
           'signal_dbm', signal_dbm,
           'reason', reason,
           'evidence', evidence
         )
    from inserted;
$$;
