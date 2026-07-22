-- object: sync_event_tombstones
-- folder: tables
-- depends_on: sync_events
create table if not exists sync_event_tombstones (
  dedupe_key text not null,
  stream_name text not null,
  payload_sha256 text,
  observed_at timestamptz not null,
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (dedupe_key, stream_name)
);

do $$
begin
  if exists (
    select 1
    from pg_constraint
    where conrelid = 'sync_event_tombstones'::regclass
      and contype = 'p'
      and pg_get_constraintdef(oid) <> 'PRIMARY KEY (dedupe_key, stream_name)'
  ) then
    alter table sync_event_tombstones drop constraint sync_event_tombstones_pkey;
  end if;

  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'sync_event_tombstones'::regclass
      and contype = 'p'
  ) then
    alter table sync_event_tombstones
      add constraint sync_event_tombstones_pkey primary key (dedupe_key, stream_name);
  end if;
end;
$$;
