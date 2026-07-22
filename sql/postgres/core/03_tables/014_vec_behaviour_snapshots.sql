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
  text_summary text not null,
  embedding_text text,     -- identity-stripped behavioural text for dense embedding
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vec_behaviour_snapshots_window_chk check (window_end > window_start)
);
