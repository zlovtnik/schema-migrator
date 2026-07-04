-- object: vec_complete_embedding_batch (replacement)
-- folder: functions
-- depends_on: vec_embedding_jobs, vec_embeddings
-- Replaces 013_vec_complete_embedding_batch.sql with the upsert WHERE clause
-- that compares source_observed_at, source_stream_name, source_sensor_id,
-- source_location_id, source_mac, embedding_dimensions, content_sha256,
-- content_text, metadata against excluded values.
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

  with payload_rows as materialized (
    -- Parse the payload once and reuse the rows for both statements.
    select
      r.job_id,
      r.lease_token,
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
      r.embedding,
      coalesce(r.metadata, '{}'::jsonb) as metadata
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
    order by
      r.source_table,
      r.source_key,
      r.embedding_model,
      r.embedding_kind,
      r.job_id
  ),
  inserted as (
    insert into vec_embeddings (
      source_table, source_key, source_observed_at, source_stream_name,
      source_sensor_id, source_location_id, source_mac,
      embedding_model, embedding_kind, embedding_dimensions,
      content_sha256, content_text, embedding, metadata,
      embedded_at, created_at, updated_at
    )
    select
      p.source_table,
      p.source_key,
      p.source_observed_at,
      p.source_stream_name,
      p.source_sensor_id,
      p.source_location_id,
      p.source_mac,
      p.embedding_model,
      p.embedding_kind,
      p.embedding_dimensions,
      p.content_sha256,
      p.content_text,
      p.embedding::vector,
      p.metadata,
      now(), now(), now()
    from payload_rows p
    order by
      p.source_table,
      p.source_key,
      p.embedding_model,
      p.embedding_kind,
      p.job_id
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
  locked as (
    -- Lock job rows in the same key order as vec_lease_embedding_jobs.
    select
      j.job_id,
      p.lease_token,
      p.content_sha256
    from (
      select job_id, lease_token, content_sha256
      from payload_rows
      order by job_id asc
    ) p
    join vec_embedding_jobs j
      on j.job_id = p.job_id
    where j.lease_token is not distinct from p.lease_token
    order by j.job_id asc
    for update skip locked
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
  select count(*) into v_count from updated;

  return v_count;
end;
$$;