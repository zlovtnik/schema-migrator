-- object: coordinator.flush_probe_batch
-- folder: functions
-- depends_on: wireless_clients, wireless_authorized_networks
drop function if exists coordinator.flush_probe_batch(jsonb);

create or replace function coordinator.flush_probe_batch(p_probes jsonb)
returns bigint
language plpgsql
as $$
declare
  v_inserted bigint := 0;
  v_probes jsonb;
  v_probe jsonb;
  v_batch_id text;
begin
  v_probes := case
    when jsonb_typeof(p_probes) = 'array' then p_probes
    when jsonb_typeof(p_probes) = 'object'
      and jsonb_typeof(p_probes->'probes') = 'array' then p_probes->'probes'
    else null
  end;

  if v_probes is null then
    raise exception 'coordinator.flush_probe_batch requires a probe array or envelope with probes array';
  end if;

  v_batch_id := coalesce(
    nullif(case when jsonb_typeof(p_probes) = 'object' then p_probes->>'batch_id' end, ''),
    md5(v_probes::text)
  );

  for v_probe in select jsonb_array_elements(v_probes)
  loop
    insert into wireless_clients (
      ssid, client_mac, known_bssid, first_seen, last_seen, probe_count,
      location_id, last_probe_batch_id
    )
    values (
      v_probe->>'ssid',
      v_probe->>'client_mac',
      (
        select max(authorized.bssid)
        from wireless_authorized_networks authorized
        where lower(authorized.ssid) = lower(v_probe->>'ssid')
          and authorized.enabled
          and (
            nullif(coalesce(v_probe->>'observed_bssid', v_probe->>'known_bssid', v_probe->>'bssid'), '') is null
            or lower(authorized.bssid) = lower(coalesce(
              v_probe->>'observed_bssid', v_probe->>'known_bssid', v_probe->>'bssid'
            ))
          )
          and (
            nullif(v_probe->>'location_id', '') is null
            or authorized.location_id = v_probe->>'location_id'
          )
        having count(*) = 1
      ),
      (v_probe->>'first_seen')::timestamptz,
      (v_probe->>'last_seen')::timestamptz,
      (v_probe->>'probe_count')::bigint,
      nullif(v_probe->>'location_id', ''),
      v_batch_id
    )
    on conflict (ssid, client_mac) do update
      set first_seen = least(wireless_clients.first_seen, excluded.first_seen),
          last_seen = greatest(wireless_clients.last_seen, excluded.last_seen),
          probe_count = case
            when wireless_clients.last_probe_batch_id is distinct from excluded.last_probe_batch_id
              then wireless_clients.probe_count + excluded.probe_count
            else wireless_clients.probe_count
          end,
          known_bssid = coalesce(excluded.known_bssid, wireless_clients.known_bssid),
          location_id = coalesce(excluded.location_id, wireless_clients.location_id),
          last_probe_batch_id = excluded.last_probe_batch_id;
    v_inserted := v_inserted + 1;
  end loop;
  return v_inserted;
end;
$$;
