-- object: vec_baseline_profiles
-- folder: tables
-- depends_on: vec_behaviour_snapshots
create table if not exists vec_baseline_profiles (
  baseline_id bigserial primary key,
  bssid text not null,
  metric text not null,
  p5 numeric not null,
  p50 numeric not null,
  p95 numeric not null,
  sample_count bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vec_baseline_profiles_unique unique (bssid, metric)
);
