-- object: vec_complete_one_embedding
-- folder: functions
-- depends_on: vec_embedding_jobs, vec_embedding_job_leases, vec_upsert_embedding
create or replace function vec_complete_one_embedding(p_payload jsonb)
returns boolean
language plpgsql
as $$
declare
  v_completed boolean := false;
begin
  if p_payload is null or jsonb_typeof(p_payload) <> 'object' then
    return false;
  end if;

  with payload as materialized (
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
    locked as materialized (
      select payload.*,
             job.source_table as job_source_table,
             job.source_key as job_source_key,
             job.embedding_model as job_embedding_model,
             job.embedding_kind as job_embedding_kind
      from payload
      join vec_embedding_jobs job on job.job_id = payload.job_id
      join vec_embedding_job_leases lease on lease.job_id = job.job_id
      where job.status = 'leased'
        and payload.lease_token is not null
        and lease.lease_token is not null
        and lease.lease_token = payload.lease_token
      for update of job, lease
    ),
    upserted as materialized (
      select
        locked.job_id,
        locked.content_sha256,
        vec_upsert_embedding(
          locked.job_source_table,
          locked.job_source_key,
          locked.source_observed_at,
          locked.source_stream_name,
          locked.source_sensor_id,
          locked.source_location_id,
          locked.source_mac,
          locked.job_embedding_model,
          locked.job_embedding_kind,
          locked.embedding_dimensions,
          locked.content_sha256,
          locked.content_text,
          locked.embedding::vector,
          coalesce(locked.metadata, '{}'::jsonb)
        ) as embedding_id
      from locked
    ),
    job_completed as (
      update vec_embedding_jobs job
         set status = 'completed',
             content_sha256 = upserted.content_sha256,
             updated_at = now()
        from upserted
       where job.job_id = upserted.job_id
      returning job.job_id
    ),
    lease_completed as (
      update vec_embedding_job_leases lease
         set completed_at = now(),
             lease_token = null,
             leased_at = null,
             locked_by = null,
             last_error = null
        from job_completed
       where lease.job_id = job_completed.job_id
      returning lease.job_id
    )
  select exists (select 1 from lease_completed)
    into v_completed;

  return v_completed;
end;
$$;
