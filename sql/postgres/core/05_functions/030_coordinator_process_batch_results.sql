-- object: coordinator.process_batch_results
-- folder: functions
-- depends_on: sync_batches, sync_jobs
create or replace function coordinator.process_batch_results(result_jsons jsonb[])
returns integer
language plpgsql
as $$
declare
  v_updated_count integer := 0;
begin
  with raw_result as (
    select payload,
           ordinality
      from unnest(result_jsons) with ordinality as raw(payload, ordinality)
     where nullif(payload->>'batch_id', '') is not null
  ),
  result as (
    select distinct on ((payload->>'batch_id')::uuid)
           (payload->>'batch_id')::uuid as batch_id,
           payload
      from raw_result
     order by (payload->>'batch_id')::uuid, ordinality desc
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
     where batch.batch_id = result.batch_id
    returning batch.job_id, batch.batch_id, batch.status, batch.last_error
  ),
  error_insert as (
    insert into sync_errors (job_id, batch_id, error_class, error_text)
    select job_id,
           batch_id,
           coalesce(nullif((select payload->>'error_class' from result where result.batch_id = batch_update.batch_id), ''), 'unknown'),
           coalesce(last_error, 'oracle load failed')
      from batch_update
     where status = 'failed'
    returning id
  ),
  affected_jobs as (
    select distinct job_id from batch_update
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
     where job.job_id in (select job_id from affected_jobs)
       and not exists (
         select 1
           from sync_batches b
          where b.job_id = job.job_id
            and b.status not in ('completed', 'failed')
       )
    returning job_id, status
  )
  select count(*) into v_updated_count from batch_update;

  return coalesce(v_updated_count, 0);
end;
$$;
