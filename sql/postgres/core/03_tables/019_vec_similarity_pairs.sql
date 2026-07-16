-- object: vec_similarity_pairs
-- folder: tables
-- depends_on: vec_embeddings
create table if not exists vec_similarity_pairs (
  pair_id bigserial primary key,
  left_embedding_id bigint not null references vec_embeddings(embedding_id) on delete cascade,
  right_embedding_id bigint not null references vec_embeddings(embedding_id) on delete cascade,
  cosine_distance double precision not null,
  cosine_similarity double precision not null,
  rank integer not null default 1,
  computed_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vec_similarity_pairs_order_chk check (left_embedding_id < right_embedding_id),
  constraint vec_similarity_pairs_distance_chk check (cosine_distance >= 0),
  constraint vec_similarity_pairs_similarity_chk check (cosine_similarity between -1 and 1)
);
