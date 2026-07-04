-- object: sync_event_payload_archives
-- folder: tables
-- depends_on: sync_events
create table if not exists sync_event_payload_archives (
  dedupe_key text primary key,
  stream_name text not null,
  observed_at timestamptz not null,
  payload_sha256 text,
  archive_uri text not null,
  payload_bytes bigint not null default 0,
  archived_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

