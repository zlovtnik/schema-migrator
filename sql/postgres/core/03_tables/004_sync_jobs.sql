-- object: sync_jobs
-- folder: tables
-- depends_on: sync_cursors
create table if not exists sync_jobs (
  job_id uuid primary key,
  stream_name text not null references sync_cursors(stream_name) deferrable initially deferred,
  status text not null,
  attempt_count integer not null default 0,
  created_at timestamptz not null default now(),
  started_at timestamptz,
  finished_at timestamptz,
  constraint chk_sync_jobs_status check (status in ('pending','running','completed','failed'))
);
