-- object: sync_batches
-- folder: tables
-- depends_on: sync_jobs
create table if not exists sync_batches (
  batch_id uuid primary key,
  job_id uuid not null references sync_jobs(job_id),
  batch_no integer not null,
  payload_ref text not null,
  status text not null,
  row_count integer,
  checksum text,
  attempt_count integer not null default 0,
  last_error text,
  dedupe_key text not null,
  stream_name text not null,
  cursor_start text not null,
  cursor_end text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint sync_batches_event_unique unique (dedupe_key, stream_name),
  constraint chk_sync_batches_status check (status in ('pending','processing','dispatched','completed','failed'))
);

alter table sync_batches
  add column if not exists stream_name text;

update sync_batches batch
   set stream_name = job.stream_name
  from sync_jobs job
 where job.job_id = batch.job_id
   and batch.stream_name is null;

alter table sync_batches
  alter column stream_name set not null;

alter table sync_batches
  drop constraint if exists sync_batches_dedupe_key_key;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'sync_batches'::regclass
      and conname = 'sync_batches_event_unique'
  ) then
    alter table sync_batches
      add constraint sync_batches_event_unique unique (dedupe_key, stream_name);
  end if;
end;
$$;
