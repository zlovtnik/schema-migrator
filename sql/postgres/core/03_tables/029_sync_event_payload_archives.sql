-- object: sync_event_payload_archives
-- folder: tables
-- depends_on: sync_events
create table if not exists sync_event_payload_archives (
  dedupe_key text not null,
  stream_name text not null,
  observed_at timestamptz not null,
  payload_sha256 text,
  archive_uri text not null,
  payload_bytes bigint not null default 0,
  archived_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (dedupe_key, stream_name)
);

do $$
begin
  if exists (
    select 1
    from pg_constraint
    where conrelid = 'sync_event_payload_archives'::regclass
      and contype = 'p'
      and pg_get_constraintdef(oid) <> 'PRIMARY KEY (dedupe_key, stream_name)'
  ) then
    alter table sync_event_payload_archives
      drop constraint sync_event_payload_archives_pkey;
  end if;

  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'sync_event_payload_archives'::regclass
      and contype = 'p'
  ) then
    alter table sync_event_payload_archives
      add constraint sync_event_payload_archives_pkey primary key (dedupe_key, stream_name);
  end if;
end;
$$;
