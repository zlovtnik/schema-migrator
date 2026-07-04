-- object: coordinator.flush_probe_batch
-- folder: functions
-- depends_on: wireless_clients, wireless_authorized_networks
create or replace function coordinator.flush_probe_batch(p_probes jsonb)
returns integer
language plpgsql
as $$
declare
  v_inserted integer := 0;
  v_probes jsonb;
  v_probe jsonb;
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

  for v_probe in select jsonb_array_elements(v_probes)
  loop
    insert into wireless_clients (ssid, client_mac, known_bssid, first_seen, last_seen, probe_count)
    values (
      v_probe->>'ssid',
      v_probe->>'client_mac',
      (select bssid from wireless_authorized_networks 
       where lower(ssid) = lower(v_probe->>'ssid') and enabled limit 1),
      (v_probe->>'first_seen')::timestamptz,
      (v_probe->>'last_seen')::timestamptz,
      (v_probe->>'probe_count')::integer
    )
    on conflict (ssid, client_mac) do update
      set first_seen = least(wireless_clients.first_seen, excluded.first_seen),
          last_seen = greatest(wireless_clients.last_seen, excluded.last_seen),
          probe_count = wireless_clients.probe_count + excluded.probe_count,
          known_bssid = coalesce(excluded.known_bssid, wireless_clients.known_bssid);
    v_inserted := v_inserted + 1;
  end loop;
  return v_inserted;
end;
$$;
