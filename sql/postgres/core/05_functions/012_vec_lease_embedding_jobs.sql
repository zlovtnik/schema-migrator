-- object: vec_lease_embedding_jobs
-- folder: functions
-- depends_on: vec_embedding_jobs, vec_worker_state
create or replace function vec_lease_embedding_jobs(
  p_limit integer default 25,
  p_worker_name text default 'vector-worker',
  p_lease interval default interval '5 minutes'
)
returns setof vec_embedding_jobs
language plpgsql
as $$
declare
  v_limit integer := greatest(p_limit, 1);
  v_count integer;
  remaining_limit integer;
begin
  -- Branch A: pending & failed jobs that are due for retry.
  -- Pull bounded candidates per kind, then interleave by kind_round so a deep
  -- event backlog cannot starve less frequent embedding kinds.
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
  candidates as (
    select
      c.job_id,
      c.embedding_kind,
      c.priority,
      c.due_at,
      k.kind_rank
    from kind_order k
    cross join lateral (
      select
        job_id,
        embedding_kind,
        priority,
        due_at
      from vec_embedding_jobs
      where embedding_kind = k.embedding_kind
        and status in ('pending', 'failed')
        and attempts < max_attempts
        and due_at <= now()
      order by priority asc, due_at asc, job_id asc
      for update skip locked
      limit v_limit
    ) c
  ),
  ranked as (
    select
      job_id,
      priority,
      due_at,
      kind_rank,
      row_number() over (
        partition by embedding_kind
        order by priority asc, due_at asc, job_id asc
      ) as kind_round
    from candidates
  ),
  selected as (
    select job_id
    from ranked
    order by kind_round asc, kind_rank asc, priority asc, due_at asc, job_id asc
    limit v_limit
  )
  update vec_embedding_jobs job
     set status = 'leased',
         attempts = job.attempts + 1,
         lease_token = md5(random()::text || clock_timestamp()::text || job.job_id::text),
         leased_at = now(),
         locked_by = p_worker_name,
         last_error = null,
         updated_at = now()
    from selected
   where job.job_id = selected.job_id
  returning job.*;

  get diagnostics v_count = row_count;
  if v_count >= v_limit then
    return;
  end if;

  remaining_limit := greatest(0, v_limit - v_count);
  if remaining_limit <= 0 then
    return;
  end if;

  -- Branch B: expired leases (worker died mid-batch).
  -- Keep the same fair ordering for reclaimed work.
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
  candidates as (
    select
      c.job_id,
      c.embedding_kind,
      c.leased_at,
      c.priority,
      k.kind_rank
    from kind_order k
    cross join lateral (
      select
        job_id,
        embedding_kind,
        leased_at,
        priority
      from vec_embedding_jobs
      where embedding_kind = k.embedding_kind
        and status = 'leased'
        and leased_at < now() - p_lease
        and attempts < max_attempts
        and due_at <= now()
      order by leased_at asc, priority asc, job_id asc
      for update skip locked
      limit remaining_limit
    ) c
  ),
  ranked as (
    select
      job_id,
      leased_at,
      priority,
      kind_rank,
      row_number() over (
        partition by embedding_kind
        order by leased_at asc, priority asc, job_id asc
      ) as kind_round
    from candidates
  ),
  selected as (
    select job_id
    from ranked
    order by kind_round asc, kind_rank asc, leased_at asc, priority asc, job_id asc
    limit remaining_limit
  )
  update vec_embedding_jobs job
     set status = 'leased',
         attempts = job.attempts + 1,
         lease_token = md5(random()::text || clock_timestamp()::text || job.job_id::text),
         leased_at = now(),
         locked_by = p_worker_name,
         last_error = null,
         updated_at = now()
    from selected
   where job.job_id = selected.job_id
  returning job.*;
end;
$$;
