-- object: vec_reembed_changed_jobs
-- folder: functions
-- depends_on: vec_embeddings_expanded, vec_embedding_jobs, vec_embedding_job_leases
create or replace function vec_reembed_changed_jobs(
  p_limit integer default 1000
)
returns integer
language plpgsql
as $$
declare
  v_count integer := 0;
begin
  with candidates as materialized (
    select
      embedding.source_table,
      embedding.source_key,
      embedding.embedding_model,
      embedding.embedding_kind
    from vec_embeddings_expanded embedding
    where embedding.content_sha256 is distinct from encode(
      digest(embedding.content_text, 'sha256'), 'hex'
    )
      and not exists (
        select 1
        from vec_embedding_jobs job
        where job.source_table = embedding.source_table
          and job.source_key = embedding.source_key
          and job.embedding_model = embedding.embedding_model
          and job.embedding_kind = embedding.embedding_kind
          and job.status in ('pending', 'leased')
      )
    order by embedding.embedded_at asc
    limit greatest(p_limit, 1)
  ),
  jobs_queued as (
    insert into vec_embedding_jobs (
      source_table, source_key, embedding_model, embedding_kind,
      status, priority, content_sha256, created_at, updated_at
    )
    select
      source_table, source_key, embedding_model, embedding_kind,
      'pending', 0, null, now(), now()
    from candidates
    on conflict (source_table, source_key, embedding_model, embedding_kind)
    do update set
      status = 'pending',
      priority = 0,
      content_sha256 = null,
      updated_at = now()
    returning job_id
  ),
  leases_reset as (
    update vec_embedding_job_leases lease
       set max_attempts = 3,
           attempts = 0,
           due_at = now(),
           lease_token = null,
           leased_at = null,
           locked_by = null,
           last_error = null,
           completed_at = null
      from jobs_queued
     where lease.job_id = jobs_queued.job_id
    returning lease.job_id
  )
  select count(*) into v_count from leases_reset;

  return v_count;
end;
$$;

comment on function vec_reembed_changed_jobs is
  'Re-queues embedding jobs for existing rows where content_sha256 no longer matches the SHA-256 of content_text. Typically invoked after text builder changes to force re-embedding of affected rows.';
