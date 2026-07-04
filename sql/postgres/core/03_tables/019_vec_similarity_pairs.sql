-- object: vec_similarity_pairs
-- folder: tables
-- depends_on: vec_embeddings
create table if not exists vec_similarity_pairs (
  pair_id bigserial primary key,
  pair_kind text not null,
  embedding_model text not null,
  embedding_kind text not null,
  left_embedding_id bigint not null references vec_embeddings(embedding_id) on delete cascade,
  right_embedding_id bigint not null references vec_embeddings(embedding_id) on delete cascade,
  left_source_table text not null,
  left_source_key text not null,
  left_source_mac text,
  left_sensor_id text,
  left_location_id text,
  left_observed_at timestamptz,
  right_source_table text not null,
  right_source_key text not null,
  right_source_mac text,
  right_sensor_id text,
  right_location_id text,
  right_observed_at timestamptz,
  cosine_distance double precision not null,
  cosine_similarity double precision not null,
  rank integer not null default 1,
  evidence jsonb not null default '{}'::jsonb,
  computed_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vec_similarity_pairs_kind_chk check (pair_kind in ('event_event', 'device_device', 'cross_sensor', 'sequence_sequence', 'timing_timing')),
  constraint vec_similarity_pairs_order_chk check (left_embedding_id < right_embedding_id),
  constraint vec_similarity_pairs_distance_chk check (cosine_distance >= 0),
  constraint vec_similarity_pairs_similarity_chk check (cosine_similarity <= 1),
  constraint vec_similarity_pairs_unique unique (pair_kind, embedding_model, embedding_kind, left_embedding_id, right_embedding_id)
);

alter table vec_similarity_pairs
  drop constraint if exists vec_similarity_pairs_kind_chk;

alter table vec_similarity_pairs
  add constraint vec_similarity_pairs_kind_chk check (pair_kind in ('event_event', 'device_device', 'cross_sensor', 'sequence_sequence', 'timing_timing'));
