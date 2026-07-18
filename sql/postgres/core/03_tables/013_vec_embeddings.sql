-- object: vec_embeddings
-- folder: tables
-- depends_on: extensions
create table if not exists vec_embeddings (
  embedding_id bigserial primary key,
  embedding_model text not null,
  embedding_kind text not null,
  embedding_dimensions integer not null,
  content_sha256 text not null,
  content_text text not null,
  embedding vector not null,
  metadata jsonb not null default '{}'::jsonb,
  embedded_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vec_embeddings_kind_chk check (embedding_kind in ('event', 'device', 'behaviour_window', 'baseline_profile', 'frame_sequence', 'infrastructure_subgraph', 'timing_profile')),
  constraint vec_embeddings_dimensions_chk check (embedding_dimensions > 0),
  constraint chk_embedding_dims_matches_embedding_dimensions check (vector_dims(embedding) = embedding_dimensions)
);

alter table vec_embeddings set (
  autovacuum_vacuum_scale_factor = 0.01,
  autovacuum_vacuum_threshold = 500,
  autovacuum_analyze_scale_factor = 0.005,
  autovacuum_analyze_threshold = 500
);

alter table vec_embeddings
  drop constraint if exists vec_embeddings_kind_chk;

alter table vec_embeddings
  add constraint vec_embeddings_kind_chk check (embedding_kind in ('event', 'device', 'behaviour_window', 'baseline_profile', 'frame_sequence', 'infrastructure_subgraph', 'timing_profile'));
