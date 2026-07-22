-- object: vec_embedding_jobs
-- folder: tables
-- depends_on: vec_embeddings
create table if not exists vec_embedding_jobs (
  job_id bigserial primary key,
  source_table text not null,
  source_key text not null,
  embedding_model text not null,
  embedding_kind text not null,
  status text not null default 'pending',
  priority integer not null default 100,
  content_sha256 text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vec_embedding_jobs_kind_chk check (embedding_kind in ('event', 'device', 'behaviour_window', 'baseline_profile', 'frame_sequence', 'infrastructure_subgraph', 'timing_profile')),
  constraint vec_embedding_jobs_status_chk check (status in ('pending', 'leased', 'completed', 'failed')),
  constraint vec_embedding_jobs_source_unique unique (source_table, source_key, embedding_model, embedding_kind)
);

alter table vec_embedding_jobs set (
  autovacuum_vacuum_scale_factor = 0.005,
  autovacuum_vacuum_threshold = 500,
  autovacuum_analyze_scale_factor = 0.005,
  autovacuum_analyze_threshold = 500
);

alter table vec_embedding_jobs
  drop constraint if exists vec_embedding_jobs_kind_chk;

alter table vec_embedding_jobs
  add constraint vec_embedding_jobs_kind_chk check (embedding_kind in ('event', 'device', 'behaviour_window', 'baseline_profile', 'frame_sequence', 'infrastructure_subgraph', 'timing_profile'));
