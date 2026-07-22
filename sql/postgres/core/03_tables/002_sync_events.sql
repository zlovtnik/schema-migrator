-- object: sync_events
-- folder: tables
-- depends_on: sync_cursors
create table if not exists sync_events (
  dedupe_key text not null,
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
  constraint sync_events_pkey primary key (dedupe_key, stream_name),
  constraint chk_sync_events_status check (status in ('pending','processing','batched','failed'))
);

do $$
begin
  if exists (
    select 1
    from pg_constraint
    where conrelid = 'sync_events'::regclass
      and contype = 'p'
      and pg_get_constraintdef(oid) <> 'PRIMARY KEY (dedupe_key, stream_name)'
  ) then
    alter table sync_events drop constraint sync_events_pkey;
  end if;

  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'sync_events'::regclass
      and contype = 'p'
  ) then
    alter table sync_events
      add constraint sync_events_pkey primary key (dedupe_key, stream_name);
  end if;
end;
$$;

alter table sync_events set (
  autovacuum_vacuum_scale_factor = 0.005,
  autovacuum_vacuum_threshold = 1000,
  autovacuum_analyze_scale_factor = 0.005,
  autovacuum_analyze_threshold = 1000
);
