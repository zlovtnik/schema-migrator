-- object: coordinator.process_batch_result
-- folder: functions
-- depends_on: sync_batches, sync_jobs
create or replace function coordinator.process_batch_result(result_json jsonb)
returns jsonb
language plpgsql
as $$
declare
  v_summary jsonb;
begin
  with result as (
    select result_json as payload
  ),
  batch_update as (
    update sync_batches batch
       set status = case result.payload->>'status'
                     when 'success' then 'completed'
                     when 'completed' then 'completed'
                     else 'failed'
                   end,
           row_count = coalesce((result.payload->>'row_count')::integer, row_count),
           checksum = nullif(result.payload->>'checksum', ''),
           last_error = nullif(result.payload->>'error_text', ''),
           updated_at = now()
      from result
     where batch.batch_id = (result.payload->>'batch_id')::uuid
    returning batch.job_id, batch.batch_id, batch.status, batch.last_error
  ),
  error_insert as (
    insert into sync_errors (job_id, batch_id, error_class, error_text)
    select job_id,
           batch_id,
           coalesce(nullif((select payload->>'error_class' from result), ''), 'unknown'),
           coalesce(last_error, 'oracle load failed')
      from batch_update
     where status = 'failed'
    returning id
  ),
  job_done as (
    update sync_jobs job
       set status = case
                     when exists (
                       select 1
                         from sync_batches b
                        where b.job_id = job.job_id
                          and b.status = 'failed'
                     ) then 'failed'
                     else 'completed'
                   end,
           finished_at = now()
     where job.job_id in (select job_id from batch_update)
       and not exists (
         select 1
           from sync_batches b
          where b.job_id = job.job_id
            and b.status not in ('completed', 'failed')
       )
    returning job_id, status
  )
  select jsonb_build_object(
           'updated', exists(select 1 from batch_update),
           'batch_id', (select batch_id::text from batch_update limit 1),
           'batch_status', (select status from batch_update limit 1),
           'job_id', (select job_id::text from batch_update limit 1),
           'job_status', (select status from job_done limit 1),
           'error_logged', exists(select 1 from error_insert)
         )
    into v_summary;

  return coalesce(v_summary, jsonb_build_object('updated', false));
end;
$$;
