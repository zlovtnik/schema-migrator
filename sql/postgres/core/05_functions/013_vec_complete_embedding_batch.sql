-- object: vec_complete_embedding_batch
-- folder: functions
-- depends_on: vec_embedding_jobs, vec_embeddings
create or replace function vec_complete_embedding_batch(p_payload jsonb)
returns integer
language plpgsql
as $$
declare
  v_count integer;
begin
  if p_payload is null or jsonb_typeof(p_payload) <> 'array' or jsonb_array_length(p_payload) = 0 then
    return 0;
  end if;

  insert into vec_embeddings (
    source_table, source_key, source_observed_at, source_stream_name,
    source_sensor_id, source_location_id, source_mac,
    embedding_model, embedding_kind, embedding_dimensions,
    content_sha256, content_text, embedding, metadata,
    embedded_at, created_at, updated_at
  )
  select
    r.source_table,
    r.source_key,
    r.source_observed_at,
    r.source_stream_name,
    r.source_sensor_id,
    r.source_location_id,
    r.source_mac,
    r.embedding_model,
    r.embedding_kind,
    r.embedding_dimensions,
    r.content_sha256,
    r.content_text,
    r.embedding::vector,
    coalesce(r.metadata, '{}'::jsonb),
    now(), now(), now()
  from jsonb_to_recordset(p_payload) as r(
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
    updated_at = now();

  update vec_embedding_jobs j
     set status = 'completed',
         content_sha256 = r.content_sha256,
         completed_at = now(),
         lease_token = null,
         leased_at = null,
         locked_by = null,
         last_error = null,
         updated_at = now()
    from jsonb_to_recordset(p_payload) as r(
      job_id bigint,
      lease_token text,
      content_sha256 text
    )
   where j.job_id = r.job_id
     and j.lease_token is not distinct from r.lease_token;

  get diagnostics v_count = row_count;
  return v_count;
end;
$$;