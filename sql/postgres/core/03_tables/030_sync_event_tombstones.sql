-- object: sync_event_tombstones
-- folder: tables
-- depends_on: sync_events
create table if not exists sync_event_tombstones (
  dedupe_key text primary key,
  stream_name text not null,
  payload_sha256 text,
  observed_at timestamptz not null,
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

