-- object: sync_errors
-- folder: tables
-- depends_on: sync_jobs, sync_batches
create table if not exists sync_errors (
  id bigserial primary key,
  job_id uuid references sync_jobs(job_id),
  batch_id uuid references sync_batches(batch_id),
  error_class text not null,
  error_text text not null,
  created_at timestamptz not null default now()
);
