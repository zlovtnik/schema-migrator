-- object: devices
-- folder: tables
-- depends_on: -
create table if not exists devices (
  mac_id text primary key,
  wg_pubkey text,
  claim_token_hash text,
  display_name text,
  username text,
  hostname text,
  os_hint text,
  mac_hint text not null,
  first_seen timestamptz not null default now(),
  last_seen timestamptz not null default now(),
  notes text,
  constraint devices_mac_id_format_chk check (
    mac_id ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'
    and lower(mac_hint) = mac_id
  )
);
