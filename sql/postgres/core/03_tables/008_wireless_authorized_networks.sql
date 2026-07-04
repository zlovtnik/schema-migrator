-- object: wireless_authorized_networks
-- folder: tables
-- depends_on: -
create table if not exists wireless_authorized_networks (
  id bigserial primary key,
  ssid text,
  bssid text,
  location_id text,
  label text,
  enabled boolean not null default true,
  notes text,
  psk_ciphertext text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint wireless_authorized_networks_identity_chk check (
    nullif(trim(coalesce(ssid, '')), '') is not null
    or nullif(trim(coalesce(bssid, '')), '') is not null
  )
);
