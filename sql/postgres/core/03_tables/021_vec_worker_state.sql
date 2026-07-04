-- object: vec_worker_state
-- folder: tables
-- depends_on: vec_embedding_jobs
create table if not exists vec_worker_state (
  worker_name text primary key,
  status text not null default 'idle',
  last_cursor text,
  last_run_started_at timestamptz,
  last_run_finished_at timestamptz,
  rows_processed bigint not null default 0,
  last_error text,
  updated_at timestamptz not null default now()
);

alter table vec_worker_state set (
  autovacuum_vacuum_scale_factor = 0,
  autovacuum_vacuum_threshold = 10,
  autovacuum_analyze_scale_factor = 0,
  autovacuum_analyze_threshold = 10
);
