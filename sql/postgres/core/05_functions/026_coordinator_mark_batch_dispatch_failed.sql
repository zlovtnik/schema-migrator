-- object: coordinator.mark_batch_dispatch_failed
-- folder: functions
-- depends_on: sync_batches, sync_jobs
create or replace function coordinator.mark_batch_dispatch_failed(
  p_load jsonb,
  p_error_text text,
  p_max_attempts integer
)
returns jsonb
language plpgsql
as $$
declare
  v_batch_id uuid := (p_load->>'batch_id')::uuid;
  v_max_attempts integer := greatest(coalesce(p_max_attempts, 5), 1);
  v_summary jsonb;
begin
  if v_batch_id is null then
    raise exception 'mark_batch_dispatch_failed requires batch_id';
  end if;

  with batch_update as (
    update sync_batches batch
       set attempt_count = batch.attempt_count + 1,
           status = case
                     when batch.attempt_count + 1 >= v_max_attempts then 'failed'
                     else 'pending'
                   end,
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
  error_insert as (
    insert into sync_errors (job_id, batch_id, error_class, error_text)
    select job_id,
           batch_id,
           'dispatch_publish_failed',
           coalesce(last_error, 'sync.oracle.load publish failed')
      from batch_update
     where status = 'failed'
    returning id
  ),
  job_update as (
    update sync_jobs job
       set status = case
                     when exists (
                       select 1
                         from batch_update
                        where batch_update.job_id = job.job_id
                          and batch_update.status = 'failed'
                     ) then 'failed'
                     else 'pending'
                   end,
           finished_at = case
                           when exists (
                             select 1
                               from batch_update
                              where batch_update.job_id = job.job_id
                                and batch_update.status = 'failed'
                           ) then now()
                           else null
                         end
     where job.job_id in (select job_id from batch_update)
    returning job.job_id, job.status
  )
  select jsonb_build_object(
           'updated', exists(select 1 from batch_update),
           'batch_id', (select batch_id::text from batch_update limit 1),
           'new_status', (select status from batch_update limit 1),
           'attempt_count', (select attempt_count from batch_update limit 1),
           'job_id', (select job_id::text from batch_update limit 1),
           'job_status', (select status from job_update limit 1),
           'error_logged', exists(select 1 from error_insert)
         )
    into v_summary;

  return coalesce(v_summary, jsonb_build_object('updated', false));
end;
$$;
