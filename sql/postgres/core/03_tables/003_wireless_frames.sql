-- object: wireless_frames
-- folder: tables
-- depends_on: -
create table if not exists wireless_frames (
  dedupe_key text primary key,
  sensor_id text,
  location_id text,
  schema_version integer not null default 1,
  frame_type text,
  frame_subtype text,
  source_mac text,
  transmitter_mac text,
  receiver_mac text,
  bssid text,
  destination_bssid text,
  bssid_oui text generated always as (
    nullif(lower(substr(regexp_replace(coalesce(nullif(bssid, ''), nullif(destination_bssid, ''), ''), '[:\-]', '', 'g'), 1, 6)), '')
  ) stored,
  ssid text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table wireless_frames
  drop constraint if exists wireless_frames_dedupe_key_fkey;
