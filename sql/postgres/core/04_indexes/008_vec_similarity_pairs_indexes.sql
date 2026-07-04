-- object: vec_similarity_pairs indexes
-- folder: indexes
-- depends_on: vec_similarity_pairs
create index if not exists vec_similarity_pairs_kind_idx
  on vec_similarity_pairs (pair_kind, embedding_model, embedding_kind, cosine_similarity desc);

create index if not exists vec_similarity_pairs_left_source_idx
  on vec_similarity_pairs (left_source_table, left_source_key);

create index if not exists vec_similarity_pairs_right_source_idx
  on vec_similarity_pairs (right_source_table, right_source_key);

create index if not exists vec_similarity_pairs_left_embedding_idx
  on vec_similarity_pairs (left_embedding_id);

create index if not exists vec_similarity_pairs_right_embedding_idx
  on vec_similarity_pairs (right_embedding_id);

create index if not exists vec_similarity_pairs_mac_idx
  on vec_similarity_pairs (left_source_mac, right_source_mac, computed_at desc);
