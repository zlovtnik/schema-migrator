-- object: wireless_frames indexes
-- folder: indexes
-- depends_on: wireless_frames
create index if not exists wireless_frames_ssid_idx on wireless_frames (ssid);

create index if not exists wireless_frames_source_mac_idx on wireless_frames (lower(source_mac));

create index if not exists wireless_frames_bssid_idx on wireless_frames (lower(bssid));

create index if not exists wireless_frames_bssid_updated_idx
  on wireless_frames (lower(bssid), updated_at desc)
  where bssid is not null;

create index if not exists wireless_frames_destination_bssid_idx on wireless_frames (lower(destination_bssid));

create index if not exists wireless_frames_destination_bssid_updated_idx
  on wireless_frames (lower(destination_bssid), updated_at desc)
  where destination_bssid is not null;

create index if not exists wireless_frames_bssid_oui_idx
  on wireless_frames (bssid_oui)
  where bssid_oui is not null;

create index if not exists wireless_frames_schema_version_idx on wireless_frames (schema_version);

create index if not exists wireless_frame_radio_signal_idx on wireless_frame_radio (signal_dbm) where signal_dbm is not null;

create index if not exists wireless_frame_network_src_ip_idx on wireless_frame_network (src_ip) where src_ip is not null;

create index if not exists wireless_frame_network_dst_ip_idx on wireless_frame_network (dst_ip) where dst_ip is not null;

create index if not exists wireless_frame_network_app_protocol_idx on wireless_frame_network (app_protocol) where app_protocol is not null;

create index if not exists wireless_frame_identity_session_key_idx on wireless_frame_identity (session_key) where session_key is not null;

create index if not exists wireless_frame_identity_fingerprint_idx on wireless_frame_identity (frame_fingerprint) where frame_fingerprint is not null;

create index if not exists wireless_frame_identity_search_tsv_idx on wireless_frame_identity using gin (search_tsv);

create index if not exists wireless_frames_common_search_idx on wireless_frames using gin ((
  lower(coalesce(sensor_id, '')) || ' ' || lower(coalesce(source_mac, '')) || ' ' || lower(coalesce(ssid, ''))
) gin_trgm_ops);

create index if not exists wireless_frame_identity_device_fingerprint_idx on wireless_frame_identity (device_fingerprint) where device_fingerprint is not null;

create index if not exists wireless_frame_security_flags_idx on wireless_frame_security (security_flags) where security_flags <> 0;

create index if not exists wireless_frame_identity_handshake_captured_idx on wireless_frame_identity (dedupe_key) where handshake_captured;

create index if not exists wireless_frame_security_tags_idx on wireless_frame_security using gin (tags);

create index if not exists wireless_frame_security_risk_score_idx
  on wireless_frame_security (risk_score desc)
  where risk_score is not null;

create index if not exists wireless_frame_identity_event_type_idx
  on wireless_frame_identity (event_type)
  where event_type is not null;
