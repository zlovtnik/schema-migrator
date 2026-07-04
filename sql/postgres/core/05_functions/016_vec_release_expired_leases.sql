-- object: vec_release_expired_leases
-- folder: functions
-- depends_on: vec_embedding_jobs
-- Release jobs whose leases have expired (worker died mid-batch).
-- Uses a fixed default of 30 minutes matching VECTOR_EMBEDDING_LEASE_SECONDS=1800.
create or replace function vec_release_expired_leases(
  p_lease_interval interval default interval '30 minutes'
)
returns integer
language plpgsql
as $$
declare
  v_count integer;
begin
  update vec_embedding_jobs
     set status = case
           when attempts >= max_attempts then 'failed'
           else 'pending'
         end,
         lease_token = null,
         leased_at = null,
         locked_by = null,
         due_at = case
           when attempts >= max_attempts then due_at
           else now()
         end,
         last_error = case
           when attempts >= max_attempts then 'lease expired after max attempts'
           else 'lease expired'
         end,
         updated_at = now()
   where status = 'leased'
     and leased_at < now() - p_lease_interval
     and job_id in (
       select job_id
       from vec_embedding_jobs
       where status = 'leased'
         and leased_at < now() - p_lease_interval
       order by job_id asc
       for update skip locked
     );

  get diagnostics v_count = row_count;
  return v_count;
end;
$$;
