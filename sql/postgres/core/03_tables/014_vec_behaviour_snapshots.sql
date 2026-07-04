-- object: vec_behaviour_snapshots
-- folder: tables
-- depends_on: sync_events
create table if not exists vec_behaviour_snapshots (
  snapshot_id bigserial primary key,
  snapshot_key text not null unique,
  source_mac text not null,
  location_id text,
  sensor_id text,
  window_start timestamptz not null,
  window_end timestamptz not null,
  event_count bigint not null default 0,
  protocol_mix jsonb not null default '{}'::jsonb,
  frame_type_distribution jsonb not null default '{}'::jsonb,
  signal_min_dbm integer,
  signal_max_dbm integer,
  signal_avg_dbm numeric(8,2),
  retry_count bigint not null default 0,
  protected_count bigint not null default 0,
  unprotected_count bigint not null default 0,
  unique_bssid_count bigint not null default 0,
  mac_rotation_indicators jsonb not null default '{}'::jsonb,
  text_summary text not null,
  embedding_text text,     -- identity-stripped behavioural text for dense embedding
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vec_behaviour_snapshots_window_chk check (window_end > window_start)
);
