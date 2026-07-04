-- object: vec_embeddings indexes
-- folder: indexes
-- depends_on: vec_embeddings
create index if not exists vec_embeddings_source_idx
  on vec_embeddings (source_table, source_key);

create index if not exists vec_embeddings_kind_model_idx
  on vec_embeddings (embedding_kind, embedding_model, embedded_at desc);

create index if not exists vec_embeddings_source_mac_idx
  on vec_embeddings (lower(source_mac), source_observed_at desc)
  where source_mac is not null;

create index if not exists vec_embeddings_event_hnsw_768_idx
  on vec_embeddings using hnsw ((embedding::vector(768)) vector_cosine_ops)
  where embedding_kind = 'event'
    and embedding_model = 'nomic-embed-text-v2-moe'
    and embedding_dimensions = 768;

create index if not exists vec_embeddings_device_hnsw_768_idx
  on vec_embeddings using hnsw ((embedding::vector(768)) vector_cosine_ops)
  where embedding_kind = 'device'
    and embedding_model = 'nomic-embed-text-v2-moe'
    and embedding_dimensions = 768;

create index if not exists vec_embeddings_behaviour_hnsw_768_idx
  on vec_embeddings using hnsw ((embedding::vector(768)) vector_cosine_ops)
  where embedding_kind = 'behaviour_window'
    and embedding_model = 'nomic-embed-text-v2-moe'
    and embedding_dimensions = 768;

create index if not exists vec_embeddings_frame_sequence_hnsw_768_idx
  on vec_embeddings using hnsw ((embedding::vector(768)) vector_cosine_ops)
  where embedding_kind = 'frame_sequence'
    and embedding_model = 'nomic-embed-text-v2-moe'
    and embedding_dimensions = 768;

create index if not exists vec_embeddings_timing_hnsw_768_idx
  on vec_embeddings using hnsw ((embedding::vector(768)) vector_cosine_ops)
  where embedding_kind = 'timing_profile'
    and embedding_model = 'nomic-embed-text-v2-moe'
    and embedding_dimensions = 768;
