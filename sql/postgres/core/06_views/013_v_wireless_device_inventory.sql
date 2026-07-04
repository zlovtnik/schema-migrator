-- object: v_wireless_device_inventory override
-- folder: views
-- depends_on: v_wireless_device_inventory
create or replace view v_wireless_device_inventory as
with recent_ingest as materialized (
  select *
  from sync_events_expanded
  where stream_name = 'wireless.audit'
    and coalesce(source_mac, payload->>'source_mac') is not null
  order by observed_at desc
  limit 20000
),
base as (
  select
    dedupe_key,
    observed_at,
    lower(coalesce(source_mac, payload->>'source_mac')) as source_mac,
    coalesce(bssid, payload->>'bssid') as bssid,
    coalesce(destination_bssid, bssid, payload->>'destination_bssid', payload->>'bssid') as destination_bssid,
    coalesce(ssid, payload->>'ssid') as ssid,
    coalesce(signal_dbm, coordinator.safe_int(payload->>'signal_dbm')) as signal_dbm,
    coalesce(location_id, payload->>'location_id') as location_id,
    coalesce(sensor_id, payload->>'sensor_id') as sensor_id,
    coalesce(username, payload->>'username') as username,
    coalesce(src_ip, payload->>'src_ip') as src_ip,
    coalesce(dst_ip, payload->>'dst_ip') as dst_ip,
    coalesce(dhcp_hostname, mdns_name, payload->>'dhcp_hostname', payload->>'mdns_name') as hostname,
    coalesce(app_protocol, payload->>'app_protocol') as app_protocol,
    coalesce(dns_query_name, payload->>'dns_query_name') as dns_query_name,
    coalesce(protected, false) as protected,
    wps_device_name,
    wps_manufacturer,
    wps_model_name,
    device_fingerprint
  from recent_ingest
),
latest as (
  select *
  from (
    select base.*, row_number() over (partition by source_mac order by observed_at desc, dedupe_key desc) as row_number
    from base
  ) ranked
  where row_number = 1
),
rollup as (
  select
    source_mac,
    min(observed_at) as first_occurred_at,
    max(observed_at) as last_occurred_at,
    count(*)::bigint as occurrence_count,
    string_agg(distinct src_ip, ', ') filter (where src_ip is not null) as ip_addresses,
    string_agg(distinct hostname, ', ') filter (where hostname is not null) as hostnames,
    string_agg(distinct app_protocol, ', ') filter (where app_protocol is not null) as services,
    string_agg(distinct dns_query_name, ', ') filter (where dns_query_name is not null) as dns_names,
    sum(case when protected then 1 else 0 end)::bigint as protected_frame_count,
    sum(case when not protected then 1 else 0 end)::bigint as open_frame_count
  from base
  group by source_mac
)
select
  rollup.source_mac as inventory_key,
  rollup.source_mac,
  rollup.first_occurred_at,
  rollup.last_occurred_at,
  rollup.first_occurred_at as first_seen,
  rollup.last_occurred_at as last_seen,
  rollup.last_occurred_at as observed_at,
  rollup.occurrence_count,
  rollup.occurrence_count as frame_count,
  latest.location_id,
  latest.sensor_id,
  latest.bssid,
  latest.destination_bssid,
  latest.ssid,
  latest.signal_dbm::text as signal_dbm,
  latest.username,
  rollup.ip_addresses,
  rollup.hostnames,
  rollup.services,
  rollup.dns_names,
  rollup.protected_frame_count,
  rollup.open_frame_count,
  latest.wps_device_name,
  latest.wps_manufacturer,
  latest.wps_model_name,
  latest.device_fingerprint,
  devices.mac_id as device_id,
  devices.display_name,
  devices.username as registered_username,
  devices.os_hint,
  coalesce(devices.hostname, latest.hostname) as hostname
from rollup
join latest on latest.source_mac = rollup.source_mac
left join devices on devices.mac_id = rollup.source_mac;
