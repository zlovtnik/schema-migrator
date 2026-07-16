-- object: vec_upsert_embedding
-- folder: functions
-- depends_on: vec_embeddings, vec_embedding_sources
create or replace function vec_upsert_embedding(
  p_source_table text,
  p_source_key text,
  p_source_observed_at timestamptz,
  p_source_stream_name text,
  p_source_sensor_id text,
  p_source_location_id text,
  p_source_mac text,
  p_embedding_model text,
  p_embedding_kind text,
  p_embedding_dimensions integer,
  p_content_sha256 text,
  p_content_text text,
  p_embedding vector,
  p_metadata jsonb
)
returns bigint
language plpgsql
as $$
declare
  v_embedding_id bigint;
begin
  perform pg_advisory_xact_lock(hashtextextended(
    concat_ws(E'\x1f', p_source_table, p_source_key, p_embedding_model, p_embedding_kind),
    0
  ));

  select embedding_id
    into v_embedding_id
  from vec_embedding_sources
  where source_table = p_source_table
    and source_key = p_source_key
    and embedding_model = p_embedding_model
    and embedding_kind = p_embedding_kind
  for update;

  if v_embedding_id is null then
    if exists (
      select 1
      from information_schema.columns
      where table_schema = current_schema()
        and table_name = 'vec_embeddings'
        and column_name = 'source_table'
    ) then
      execute $legacy_insert$
        insert into vec_embeddings (
          source_table, source_key, source_observed_at, source_stream_name,
          source_sensor_id, source_location_id, source_mac,
          embedding_model, embedding_kind, embedding_dimensions,
          content_sha256, content_text, embedding, metadata,
          embedded_at, created_at, updated_at
        )
        values (
          $1, $2, $3, $4, $5, $6, $7,
          $8, $9, $10, $11, $12, $13, $14,
          now(), now(), now()
        )
        returning embedding_id
      $legacy_insert$
      into v_embedding_id
      using
        p_source_table, p_source_key, p_source_observed_at, p_source_stream_name,
        p_source_sensor_id, p_source_location_id, p_source_mac,
        p_embedding_model, p_embedding_kind, p_embedding_dimensions,
        p_content_sha256, p_content_text, p_embedding,
        coalesce(p_metadata, '{}'::jsonb);
    else
      insert into vec_embeddings (
        embedding_model, embedding_kind, embedding_dimensions,
        content_sha256, content_text, embedding, metadata,
        embedded_at, created_at, updated_at
      )
      values (
        p_embedding_model, p_embedding_kind, p_embedding_dimensions,
        p_content_sha256, p_content_text, p_embedding, coalesce(p_metadata, '{}'::jsonb),
        now(), now(), now()
      )
      returning embedding_id into v_embedding_id;
    end if;

    insert into vec_embedding_sources (
      embedding_id, source_table, source_key, source_observed_at,
      source_stream_name, source_sensor_id, source_location_id, source_mac,
      embedding_model, embedding_kind
    )
    values (
      v_embedding_id, p_source_table, p_source_key, p_source_observed_at,
      p_source_stream_name, p_source_sensor_id, p_source_location_id, p_source_mac,
      p_embedding_model, p_embedding_kind
    )
    on conflict (embedding_id) do update set
      source_table = excluded.source_table,
      source_key = excluded.source_key,
      source_observed_at = excluded.source_observed_at,
      source_stream_name = excluded.source_stream_name,
      source_sensor_id = excluded.source_sensor_id,
      source_location_id = excluded.source_location_id,
      source_mac = excluded.source_mac,
      embedding_model = excluded.embedding_model,
      embedding_kind = excluded.embedding_kind;
  else
    update vec_embeddings
       set embedding_dimensions = p_embedding_dimensions,
           content_sha256 = p_content_sha256,
           content_text = p_content_text,
           embedding = p_embedding,
           metadata = coalesce(p_metadata, '{}'::jsonb),
           embedded_at = now(),
           updated_at = now()
     where embedding_id = v_embedding_id;

    update vec_embedding_sources
       set source_observed_at = p_source_observed_at,
           source_stream_name = p_source_stream_name,
           source_sensor_id = p_source_sensor_id,
           source_location_id = p_source_location_id,
           source_mac = p_source_mac
     where embedding_id = v_embedding_id;
  end if;

  return v_embedding_id;
end;
$$;
