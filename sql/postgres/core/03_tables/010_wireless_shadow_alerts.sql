-- object: wireless_shadow_alerts
-- folder: tables
-- depends_on: devices
create table if not exists wireless_shadow_alerts (
  source_mac text primary key,
  first_occurred_at timestamptz not null,
  last_occurred_at timestamptz not null,
  occurrence_count bigint not null default 1,
  destination_bssid text,
  ssid text,
  sensor_id text,
  location_id text,
  signal_dbm integer,
  reason text not null,
  evidence jsonb not null default '{}'::jsonb,
  resolved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint wireless_shadow_alerts_source_mac_format_chk check (source_mac ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$')
);
