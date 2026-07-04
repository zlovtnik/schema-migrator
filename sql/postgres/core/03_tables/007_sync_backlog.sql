-- object: sync_backlog
-- folder: tables
-- depends_on: sync_events
create table if not exists sync_backlog (
  dedupe_key text primary key,
  stream_name text not null,
  payload jsonb not null,
  failure_stage text not null default 'pre_publish',
  status text not null default 'pending',
  attempt_count integer not null default 0,
  last_error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint chk_sync_backlog_failure_stage check (failure_stage in ('pre_publish','post_publish')),
  constraint chk_sync_backlog_status check (status in ('pending','synced','sync_failed','failed'))
);

alter table if exists sync_backlog
  add column if not exists failure_stage text not null default 'pre_publish';

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'chk_sync_backlog_failure_stage'
      and conrelid = 'sync_backlog'::regclass
      and contype = 'c'
  ) then
    alter table sync_backlog
      add constraint chk_sync_backlog_failure_stage
      check (failure_stage in ('pre_publish','post_publish'));
  end if;
end;
$$;
