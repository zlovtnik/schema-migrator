-- object: graph SSID indexes
-- folder: indexes
-- depends_on: wireless_frames, wireless_clients, wireless_shadow_alerts, wireless_authorized_networks
create index if not exists wireless_frames_graph_ssid_trgm_idx
  on wireless_frames using gin (lower(coalesce(ssid, '')) gin_trgm_ops)
  where nullif(ssid, '') is not null;

create index if not exists wireless_clients_graph_ssid_trgm_idx
  on wireless_clients using gin (lower(coalesce(ssid, '')) gin_trgm_ops)
  where nullif(ssid, '') is not null;

create index if not exists wireless_shadow_alerts_graph_ssid_trgm_idx
  on wireless_shadow_alerts using gin (lower(coalesce(ssid, '')) gin_trgm_ops)
  where nullif(ssid, '') is not null;

create index if not exists wireless_authorized_networks_graph_ssid_trgm_idx
  on wireless_authorized_networks using gin (lower(coalesce(ssid, '')) gin_trgm_ops)
  where nullif(ssid, '') is not null;
