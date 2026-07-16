-- object: vec_similarity_pairs_expanded
-- folder: views
-- depends_on: vec_similarity_pairs, vec_similarity_pair_meta, vec_embedding_sources
create or replace view vec_similarity_pairs_expanded as
select
  pair.pair_id,
  meta.pair_kind,
  meta.embedding_model,
  meta.embedding_kind,
  pair.left_embedding_id,
  pair.right_embedding_id,
  meta.left_source_table,
  meta.left_source_key,
  left_source.source_mac as left_source_mac,
  left_source.source_sensor_id as left_sensor_id,
  left_source.source_location_id as left_location_id,
  left_source.source_observed_at as left_observed_at,
  meta.right_source_table,
  meta.right_source_key,
  right_source.source_mac as right_source_mac,
  right_source.source_sensor_id as right_sensor_id,
  right_source.source_location_id as right_location_id,
  right_source.source_observed_at as right_observed_at,
  pair.cosine_distance,
  pair.cosine_similarity,
  pair.rank,
  meta.evidence,
  pair.computed_at,
  pair.created_at,
  pair.updated_at
from vec_similarity_pairs pair
join vec_similarity_pair_meta meta using (pair_id)
join vec_embedding_sources left_source on left_source.embedding_id = pair.left_embedding_id
join vec_embedding_sources right_source on right_source.embedding_id = pair.right_embedding_id;
