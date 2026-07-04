-- object: coordinator.lookup_device_by_mac
-- folder: functions
-- depends_on: devices
create or replace function coordinator.lookup_device_by_mac(p_mac text)
returns jsonb
language sql
as $$
  select jsonb_build_object(
    'device_id', mac_id,
    'username', username,
    'display_name', display_name,
    'hostname', hostname
  )
  from devices
  where lower(mac_id) = lower(p_mac)
  limit 1;
$$;
