-- object: v_wireless_threats
-- folder: views
-- depends_on: v_wireless_audit_with_devices
drop view if exists v_wireless_threats;

create view v_wireless_threats as
with resolved as (
  select
    observed_at,
    coalesce(ssid, payload->>'ssid') as ssid,
    coalesce(bssid, payload->>'bssid') as bssid,
    coalesce(destination_bssid, payload->>'destination_bssid', payload->>'bssid') as destination_bssid,
    coalesce(source_mac, payload->>'source_mac') as source_mac,
    coalesce(sensor_id, payload->>'sensor_id') as sensor_id,
    coalesce(transmitter_mac, payload->>'transmitter_mac') as transmitter_mac,
    coalesce(receiver_mac, payload->>'receiver_mac') as receiver_mac,
    coalesce(frame_subtype, payload->>'frame_subtype') as frame_subtype,
    coalesce(signal_dbm::text, payload->>'signal_dbm') as signal_dbm,
    coalesce(noise_dbm::text, payload->>'noise_dbm') as noise_dbm,
    coalesce(frequency_mhz::text, payload->>'frequency_mhz') as frequency_mhz,
    coalesce(data_rate_kbps::text, payload->>'data_rate_kbps') as data_rate_kbps,
    coalesce(raw_len::text, payload->>'raw_len') as raw_len,
    coalesce(frame_control_flags::text, payload->>'frame_control_flags') as frame_control_flags,
    coalesce(more_data::text, payload->>'more_data') as more_data,
    coalesce(retry::text, payload->>'retry') as retry,
    coalesce(power_save::text, payload->>'power_save') as power_save,
    coalesce(protected::text, payload->>'protected') as protected,
    coalesce(location_id, payload->>'location_id') as location_id,
    coalesce(risk_score::text, payload->>'risk_score') as risk_score,
    coalesce(identity_source, payload->>'identity_source') as identity_source,
    coalesce(username, payload->>'username') as username,
    case
      when tags is not null and tags <> '[]'::jsonb then tags
      else coordinator.safe_jsonb_array(payload->'tags')
    end as resolved_tags,
    payload_archived,
    payload_archive_uri,
    payload_archived_at,
    security_flags,
    wps_device_name,
    wps_manufacturer,
    wps_model_name,
    device_fingerprint,
    handshake_captured
  from sync_events_expanded
  where stream_name = 'wireless.audit'
)
select
  observed_at,
  ssid,
  bssid,
  destination_bssid,
  source_mac,
  sensor_id,
  transmitter_mac,
  receiver_mac,
  frame_subtype,
  signal_dbm,
  noise_dbm,
  frequency_mhz,
  data_rate_kbps,
  raw_len,
  frame_control_flags,
  more_data,
  retry,
  power_save,
  protected,
  location_id,
  risk_score,
  identity_source,
  username,
  resolved_tags as tags,
  payload_archived,
  payload_archive_uri,
  payload_archived_at,
  security_flags,
  wps_device_name,
  wps_manufacturer,
  wps_model_name,
  device_fingerprint,
  handshake_captured
from resolved
where coordinator.has_threat_tag(resolved_tags)
  or handshake_captured
order by observed_at desc;
