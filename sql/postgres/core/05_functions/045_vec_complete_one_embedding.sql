-- object: vec_complete_one_embedding
-- folder: functions
-- depends_on: vec_embedding_jobs, vec_embeddings
create or replace function vec_complete_one_embedding(p_payload jsonb)
returns boolean
language plpgsql
as $$
begin
  if p_payload is null or jsonb_typeof(p_payload) <> 'object' then
    return false;
  end if;

  return exists (
    with payload as (
      select *
      from jsonb_to_record(p_payload) as r(
        job_id bigint,
        lease_token text,
        source_table text,
        source_key text,
        source_observed_at timestamptz,
        source_stream_name text,
        source_sensor_id text,
        source_location_id text,
        source_mac text,
        embedding_model text,
        embedding_kind text,
        embedding_dimensions integer,
        content_sha256 text,
        content_text text,
        embedding text,
        metadata jsonb
      )
    ),
    locked as (
      select p.*
      from payload p
      join vec_embedding_jobs j
        on j.job_id = p.job_id
       and j.lease_token is not distinct from p.lease_token
      for update of j
    ),
    upserted as (
      insert into vec_embeddings (
        source_table, source_key, source_observed_at, source_stream_name,
        source_sensor_id, source_location_id, source_mac,
        embedding_model, embedding_kind, embedding_dimensions,
        content_sha256, content_text, embedding, metadata,
        embedded_at, created_at, updated_at
      )
      select
        source_table,
        source_key,
        source_observed_at,
        source_stream_name,
        source_sensor_id,
        source_location_id,
        source_mac,
        embedding_model,
        embedding_kind,
        embedding_dimensions,
        content_sha256,
        content_text,
        embedding::vector,
        coalesce(metadata, '{}'::jsonb),
        now(), now(), now()
      from locked
      on conflict (source_table, source_key, embedding_model, embedding_kind)
      do update set
        source_observed_at = excluded.source_observed_at,
        source_stream_name = excluded.source_stream_name,
        source_sensor_id = excluded.source_sensor_id,
        source_location_id = excluded.source_location_id,
        source_mac = excluded.source_mac,
        embedding_dimensions = excluded.embedding_dimensions,
        content_sha256 = excluded.content_sha256,
        content_text = excluded.content_text,
        embedding = excluded.embedding,
        metadata = excluded.metadata,
        embedded_at = now(),
        updated_at = now()
      where vec_embeddings.source_observed_at is distinct from excluded.source_observed_at
         or vec_embeddings.source_stream_name is distinct from excluded.source_stream_name
         or vec_embeddings.source_sensor_id is distinct from excluded.source_sensor_id
         or vec_embeddings.source_location_id is distinct from excluded.source_location_id
         or vec_embeddings.source_mac is distinct from excluded.source_mac
         or vec_embeddings.embedding_dimensions is distinct from excluded.embedding_dimensions
         or vec_embeddings.content_sha256 is distinct from excluded.content_sha256
         or vec_embeddings.content_text is distinct from excluded.content_text
         or vec_embeddings.metadata is distinct from excluded.metadata
      returning 1
    ),
    updated as (
      update vec_embedding_jobs j
         set status = 'completed',
             content_sha256 = locked.content_sha256,
             completed_at = now(),
             lease_token = null,
             leased_at = null,
             locked_by = null,
             last_error = null,
             updated_at = now()
        from locked
       where j.job_id = locked.job_id
         and j.lease_token is not distinct from locked.lease_token
      returning 1
    )
    select 1 from updated
  );
end;
$$;
