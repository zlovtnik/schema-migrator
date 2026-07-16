-- object: vec_embeddings_expanded
-- folder: views
-- depends_on: vec_embeddings, vec_embedding_sources
create or replace view vec_embeddings_expanded as
select
  embedding.embedding_id,
  source.source_table,
  source.source_key,
  source.source_observed_at,
  source.source_stream_name,
  source.source_sensor_id,
  source.source_location_id,
  source.source_mac,
  embedding.embedding_model,
  embedding.embedding_kind,
  embedding.embedding_dimensions,
  embedding.content_sha256,
  embedding.content_text,
  embedding.embedding,
  embedding.metadata,
  embedding.embedded_at,
  embedding.created_at,
  embedding.updated_at
from vec_embeddings embedding
join vec_embedding_sources source using (embedding_id);
