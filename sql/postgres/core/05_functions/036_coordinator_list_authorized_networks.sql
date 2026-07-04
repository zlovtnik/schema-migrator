-- object: coordinator.list_authorized_networks
-- folder: functions
-- depends_on: wireless_authorized_networks
create or replace function coordinator.list_authorized_networks()
returns jsonb
language sql
as $$
  select coalesce(jsonb_agg(
    jsonb_build_object(
      'ssid', ssid,
      'bssid', lower(bssid),
      'location_id', location_id,
      'label', label,
      'enabled', enabled
    )
  ), '[]'::jsonb)
  from wireless_authorized_networks
  where enabled;
$$;
