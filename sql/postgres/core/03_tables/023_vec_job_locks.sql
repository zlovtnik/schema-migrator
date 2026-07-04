-- object: vec_job_locks
-- folder: tables
-- depends_on: -
create table if not exists vec_job_locks (
  job_name text primary key,
  locked_at timestamptz not null default now(),
  locked_by text
);
