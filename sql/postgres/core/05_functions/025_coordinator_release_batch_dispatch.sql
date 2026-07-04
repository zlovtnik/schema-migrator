-- object: coordinator.release_batch_dispatch
-- folder: functions
-- depends_on: sync_batches, sync_jobs
create or replace function coordinator.release_batch_dispatch(
  p_load jsonb,
  p_error_text text
)
returns jsonb
language plpgsql
as $$
declare
  v_batch_id uuid := (p_load->>'batch_id')::uuid;
  v_summary jsonb;
begin
  if v_batch_id is null then
    raise exception 'release_batch_dispatch requires batch_id';
  end if;

  with batch_update as (
    update sync_batches batch
       set attempt_count = greatest(batch.attempt_count - 1, 0),
           status = 'pending',
           last_error = nullif(p_error_text, ''),
           updated_at = now()
     where batch.batch_id = v_batch_id
       and batch.status = 'dispatched'
    returning batch.job_id,
              batch.batch_id,
              batch.status,
              batch.attempt_count,
              batch.last_error
  ),
  job_update as (
    update sync_jobs job
       set status = 'pending',
           finished_at = null
     where job.job_id in (select job_id from batch_update)
    returning job.job_id, job.status
  )
  select jsonb_build_object(
           'updated', exists(select 1 from batch_update),
           'batch_id', (select batch_id::text from batch_update limit 1),
           'new_status', (select status from batch_update limit 1),
           'attempt_count', (select attempt_count from batch_update limit 1),
           'job_id', (select job_id::text from batch_update limit 1),
           'job_status', (select status from job_update limit 1)
         )
    into v_summary;

  return coalesce(v_summary, jsonb_build_object('updated', false));
end;
$$;
