-- object: vec_release_expired_leases
-- folder: functions
-- depends_on: vec_embedding_jobs, vec_embedding_job_leases
create or replace function vec_release_expired_leases(
  p_lease_interval interval default interval '30 minutes'
)
returns integer
language plpgsql
as $$
declare
  v_count integer;
begin
  with selected as materialized (
    select
      job.job_id,
      lease.attempts >= lease.max_attempts as exhausted
    from vec_embedding_jobs job
    join vec_embedding_job_leases lease using (job_id)
    where job.status = 'leased'
      and lease.leased_at < now() - p_lease_interval
    order by job.job_id
    for update of job, lease skip locked
  ),
  leases_released as (
    update vec_embedding_job_leases lease
       set lease_token = null,
           leased_at = null,
           locked_by = null,
           due_at = case when selected.exhausted then lease.due_at else now() end,
           last_error = case
             when selected.exhausted then 'lease expired after max attempts'
             else 'lease expired'
           end
      from selected
     where lease.job_id = selected.job_id
    returning lease.job_id
  ),
  jobs_released as (
    update vec_embedding_jobs job
       set status = case when selected.exhausted then 'failed' else 'pending' end,
           updated_at = now()
      from selected
      join leases_released using (job_id)
     where job.job_id = selected.job_id
    returning job.job_id
  )
  select count(*) into v_count from jobs_released;

  return v_count;
end;
$$;
