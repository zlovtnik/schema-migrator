-- object: wireless_clients
-- folder: tables
-- depends_on: wireless_authorized_networks
create table if not exists wireless_clients (
  ssid text not null,
  client_mac text not null,
  known_bssid text,
  first_seen timestamptz not null default now(),
  last_seen timestamptz not null default now(),
  probe_count integer not null default 1,
  location_id text,
  last_probe_batch_id text,
  primary key (ssid, client_mac)
);

alter table wireless_clients
  add column if not exists last_probe_batch_id text;
