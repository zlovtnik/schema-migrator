-- object: sync_events
-- folder: tables
-- depends_on: sync_cursors
create table if not exists sync_events (
  dedupe_key text primary key,
  stream_name text not null,
  observed_at timestamptz not null,
  payload_ref text not null,
  payload jsonb,
  payload_sha256 text,
  status text not null default 'pending',
  attempt_count integer not null default 0,
  last_error text,
  producer text not null default 'unknown',
  event_kind text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint chk_sync_events_status check (status in ('pending','processing','batched','failed'))
);

alter table sync_events set (
  autovacuum_vacuum_scale_factor = 0.005,
  autovacuum_vacuum_threshold = 1000,
  autovacuum_analyze_scale_factor = 0.005,
  autovacuum_analyze_threshold = 1000
);
