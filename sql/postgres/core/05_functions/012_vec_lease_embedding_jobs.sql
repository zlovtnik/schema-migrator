-- object: vec_lease_embedding_jobs
-- folder: functions
-- depends_on: vec_embedding_jobs, vec_embedding_job_leases, vec_worker_state
drop function if exists vec_lease_embedding_jobs(integer, text, interval);

create or replace function vec_lease_embedding_jobs(
  p_limit integer default 25,
  p_worker_name text default 'vector-worker',
  p_lease interval default interval '5 minutes'
)
returns table (
  job_id bigint,
  source_table text,
  source_key text,
  embedding_model text,
  embedding_kind text,
  status text,
  priority integer,
  attempts integer,
  max_attempts integer,
  lease_token text,
  leased_at timestamptz,
  locked_by text,
  due_at timestamptz,
  content_sha256 text,
  last_error text,
  completed_at timestamptz,
  created_at timestamptz,
  updated_at timestamptz
)
language plpgsql
as $$
#variable_conflict use_column
begin
  return query
  with kind_order(embedding_kind, kind_rank) as (
    values
      ('event', 1),
      ('device', 2),
      ('behaviour_window', 3),
      ('baseline_profile', 4),
      ('frame_sequence', 5),
      ('infrastructure_subgraph', 6),
      ('timing_profile', 7)
  ),
  candidates as materialized (
    select
      candidate.job_id,
      candidate.embedding_kind,
      candidate.priority,
      candidate.due_at,
      candidate.leased_at,
      candidate.reclaim_rank,
      kind.kind_rank
    from kind_order kind
    cross join lateral (
      select
        job.job_id,
        job.embedding_kind,
        job.priority,
        lease.due_at,
        lease.leased_at,
        case when job.status in ('pending', 'failed') then 0 else 1 end as reclaim_rank
      from vec_embedding_jobs job
      join vec_embedding_job_leases lease using (job_id)
      where job.embedding_kind = kind.embedding_kind
        and lease.attempts < lease.max_attempts
        and lease.due_at <= now()
        and (
          job.status in ('pending', 'failed')
          or (
            job.status = 'leased'
            and lease.leased_at < now() - p_lease
          )
        )
      order by
        reclaim_rank,
        case when job.status = 'leased' then lease.leased_at end asc,
        job.priority asc,
        lease.due_at asc,
        job.job_id asc
      for update of job, lease skip locked
      limit greatest(p_limit, 1)
    ) candidate
  ),
  ranked as (
    select
      job_id,
      priority,
      due_at,
      leased_at,
      reclaim_rank,
      kind_rank,
      row_number() over (
        partition by embedding_kind
        order by reclaim_rank, priority, due_at, job_id
      ) as kind_round
    from candidates
  ),
  selected as (
    select job_id
    from ranked
    order by reclaim_rank, kind_round, kind_rank, priority, due_at, job_id
    limit greatest(p_limit, 1)
  ),
  leases_updated as (
    update vec_embedding_job_leases lease
       set attempts = lease.attempts + 1,
           lease_token = md5(random()::text || clock_timestamp()::text || lease.job_id::text),
           leased_at = now(),
           locked_by = p_worker_name,
           last_error = null
      from selected
     where lease.job_id = selected.job_id
    returning lease.*
  ),
  jobs_updated as (
    update vec_embedding_jobs job
       set status = 'leased',
           updated_at = now()
      from leases_updated lease
     where job.job_id = lease.job_id
    returning job.*
  )
  select
    job.job_id,
    job.source_table,
    job.source_key,
    job.embedding_model,
    job.embedding_kind,
    job.status,
    job.priority,
    lease.attempts,
    lease.max_attempts,
    lease.lease_token,
    lease.leased_at,
    lease.locked_by,
    lease.due_at,
    job.content_sha256,
    lease.last_error,
    lease.completed_at,
    job.created_at,
    job.updated_at
  from jobs_updated job
  join leases_updated lease using (job_id)
  order by job.priority, lease.due_at, job.job_id;
end;
$$;
