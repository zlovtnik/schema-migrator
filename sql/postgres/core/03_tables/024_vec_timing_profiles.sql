-- object: vec_timing_profiles
-- folder: tables
-- depends_on: sync_events
create table if not exists vec_timing_profiles (
  profile_id bigserial primary key,
  profile_key text not null unique,
  source_mac text not null,
  sensor_id text,
  location_id text,
  window_start timestamptz not null,
  window_end timestamptz not null,
  tsft_p50_us numeric,
  tsft_p95_us numeric,
  tsft_jitter numeric,
  wall_p50_ms numeric,
  wall_jitter_ms numeric,
  beacon_interval_median_ms numeric,
  beacon_jitter_ms numeric,
  embedding_text text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vec_timing_profiles_window_chk check (window_end > window_start)
);
