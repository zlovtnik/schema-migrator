-- object: vec_similarity_pairs indexes
-- folder: indexes
-- depends_on: vec_similarity_pairs
create index if not exists vec_similarity_pair_meta_kind_idx
  on vec_similarity_pair_meta (pair_kind, embedding_model, embedding_kind, pair_id);

create index if not exists vec_similarity_pairs_score_idx
  on vec_similarity_pairs (cosine_similarity desc, pair_id);

create index if not exists vec_similarity_pair_meta_left_source_idx
  on vec_similarity_pair_meta (left_source_table, left_source_key);

create index if not exists vec_similarity_pair_meta_right_source_idx
  on vec_similarity_pair_meta (right_source_table, right_source_key);

create index if not exists vec_similarity_pairs_left_embedding_idx
  on vec_similarity_pairs (left_embedding_id);

create index if not exists vec_similarity_pairs_right_embedding_idx
  on vec_similarity_pairs (right_embedding_id);

create index if not exists vec_similarity_pairs_computed_idx
  on vec_similarity_pairs (computed_at desc, pair_id);
