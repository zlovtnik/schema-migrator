-- object: vec_reap_stale_workers
-- folder: functions
-- depends_on: vec_worker_state
-- Mark worker rows stale if heartbeat (updated_at) is older than threshold.
-- These are ghost workers from dead containers with no active lease.
create or replace function vec_reap_stale_workers(
  p_stale_after interval default interval '5 minutes'
)
returns integer
language plpgsql
as $$
declare
  v_count integer;
begin
  update vec_worker_state
     set status = 'stale',
         updated_at = now()
   where status = 'running'
     and updated_at < now() - p_stale_after;

  get diagnostics v_count = row_count;
  return v_count;
end;
$$;
