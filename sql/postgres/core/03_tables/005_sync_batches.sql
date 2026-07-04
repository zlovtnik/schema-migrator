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
  dedupe_key text not null unique,
  cursor_start text not null,
  cursor_end text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint chk_sync_batches_status check (status in ('pending','processing','dispatched','completed','failed'))
);
