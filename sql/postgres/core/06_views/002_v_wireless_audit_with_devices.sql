-- object: v_wireless_audit_with_devices
-- folder: views
-- depends_on: sync_events_expanded, devices
drop view if exists v_wireless_audit_with_devices;

create view v_wireless_audit_with_devices as
select
  ssi.dedupe_key,
  ssi.observed_at,
  ssi.stream_name,
  ssi.status,
  ssi.producer,
  ssi.event_kind,
  ssi.event_type,
  ssi.payload_archived,
  ssi.payload_archive_uri,
  ssi.archived_payload_bytes,
  ssi.payload_archived_at,
  coalesce(ssi.schema_version, nullif(ssi.payload->>'schema_version', '')::integer, 1) as schema_version,
  coalesce(ssi.frame_type, ssi.payload->>'frame_type') as frame_type,
  coalesce(ssi.source_mac, ssi.payload->>'source_mac') as source_mac,
  coalesce(ssi.transmitter_mac, ssi.payload->>'transmitter_mac') as transmitter_mac,
  coalesce(ssi.receiver_mac, ssi.payload->>'receiver_mac') as receiver_mac,
  coalesce(ssi.bssid, ssi.payload->>'bssid') as bssid,
  coalesce(ssi.destination_bssid, ssi.payload->>'destination_bssid', ssi.payload->>'bssid') as destination_bssid,
  coalesce(ssi.ssid, ssi.payload->>'ssid') as ssid,
  coalesce(ssi.frame_subtype, ssi.payload->>'frame_subtype') as frame_subtype,
  coalesce(ssi.signal_dbm::text, ssi.payload->>'signal_dbm') as signal_dbm,
  coalesce(ssi.noise_dbm::text, ssi.payload->>'noise_dbm') as noise_dbm,
  coalesce(ssi.frequency_mhz::text, ssi.payload->>'frequency_mhz') as frequency_mhz,
  coalesce(ssi.channel_number::text, ssi.payload->>'channel_number') as channel_number,
  coalesce(ssi.channel_flags::text, ssi.payload->>'channel_flags') as channel_flags,
  coalesce(ssi.signal_status, ssi.payload->>'signal_status') as signal_status,
  coalesce(ssi.qos_tid::text, ssi.payload->>'qos_tid') as qos_tid,
  coalesce(ssi.ethertype::text, ssi.payload->>'ethertype') as ethertype,
  coalesce(ssi.src_ip, ssi.payload->>'src_ip') as src_ip,
  coalesce(ssi.dst_ip, ssi.payload->>'dst_ip') as dst_ip,
  coalesce(ssi.src_port::text, ssi.payload->>'src_port') as src_port,
  coalesce(ssi.dst_port::text, ssi.payload->>'dst_port') as dst_port,
  coalesce(ssi.app_protocol, ssi.payload->>'app_protocol') as app_protocol,
  coalesce(ssi.session_key, ssi.payload->>'session_key') as session_key,
  coalesce(ssi.retransmit_key, ssi.payload->>'retransmit_key') as retransmit_key,
  coalesce(ssi.frame_fingerprint, ssi.payload->>'frame_fingerprint') as frame_fingerprint,
  coalesce(ssi.payload_visibility, ssi.payload->>'payload_visibility') as payload_visibility,
  coalesce(ssi.large_frame::text, ssi.payload->>'large_frame') as large_frame,
  coalesce(ssi.mixed_encryption::text, ssi.payload->>'mixed_encryption') as mixed_encryption,
  coalesce(ssi.dedupe_or_replay_suspect::text, ssi.payload->>'dedupe_or_replay_suspect') as dedupe_or_replay_suspect,
  coalesce(ssi.dhcp_hostname, ssi.payload->>'dhcp_hostname') as dhcp_hostname,
  coalesce(ssi.dns_query_name, ssi.payload->>'dns_query_name') as dns_query_name,
  coalesce(ssi.mdns_name, ssi.payload->>'mdns_name') as mdns_name,
  coalesce(ssi.data_rate_kbps::text, ssi.payload->>'data_rate_kbps') as data_rate_kbps,
  coalesce(ssi.antenna_id::text, ssi.payload->>'antenna_id') as antenna_id,
  coalesce(ssi.tsft::text, ssi.payload->>'tsft') as tsft,
  coalesce(ssi.raw_len::text, ssi.payload->>'raw_len') as raw_len,
  coalesce(ssi.frame_control_flags::text, ssi.payload->>'frame_control_flags') as frame_control_flags,
  coalesce(ssi.more_data::text, ssi.payload->>'more_data') as more_data,
  coalesce(ssi.retry::text, ssi.payload->>'retry') as retry,
  coalesce(ssi.power_save::text, ssi.payload->>'power_save') as power_save,
  coalesce(ssi.protected::text, ssi.payload->>'protected') as protected,
  coalesce(ssi.location_id, ssi.payload->>'location_id') as location_id,
  coalesce(ssi.sensor_id, ssi.payload->>'sensor_id') as sensor_id,
  coalesce(ssi.risk_score::text, ssi.payload->>'risk_score') as risk_score,
  coalesce(ssi.identity_source, ssi.payload->>'identity_source') as identity_source,
  coalesce(ssi.username, ssi.payload->>'username') as username,
  case
    when ssi.tags is not null and ssi.tags <> '[]'::jsonb then ssi.tags
    else coordinator.safe_jsonb_array(ssi.payload->'tags')
  end as tags,
  ssi.security_flags,
  ssi.wps_device_name,
  ssi.wps_manufacturer,
  ssi.wps_model_name,
  ssi.device_fingerprint,
  ssi.handshake_captured,
  coalesce(d_src.mac_id, d_bssid.mac_id) as device_id,
  coalesce(d_src.display_name, d_bssid.display_name) as display_name,
  coalesce(d_src.username, d_bssid.username) as registered_username,
  coalesce(d_src.os_hint, d_bssid.os_hint) as os_hint,
  coalesce(d_src.hostname, d_bssid.hostname, ssi.dhcp_hostname, ssi.payload->>'dhcp_hostname') as hostname
from sync_events_expanded ssi
left join devices d_src
  on lower(d_src.mac_hint) = lower(coalesce(ssi.source_mac, ssi.payload->>'source_mac'))
left join devices d_bssid
  on lower(d_bssid.mac_hint) = lower(coalesce(ssi.bssid, ssi.payload->>'bssid'))
where ssi.stream_name = 'wireless.audit';
