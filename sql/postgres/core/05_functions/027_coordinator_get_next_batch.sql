-- object: coordinator.get_next_batch
-- folder: functions
-- depends_on: sync_batches, sync_jobs
create or replace function coordinator.get_next_batch(
  p_oracle_stream_names text[]
)
returns jsonb
language plpgsql
as $$
declare
  v_payload jsonb;
begin
  with picked as (
    select batch.batch_id,
           batch.payload_ref,
           event.payload as stored_payload
      from sync_batches batch
      join sync_jobs job on job.job_id = batch.job_id
      left join sync_events event on event.dedupe_key = batch.dedupe_key
     where batch.status = 'pending'
       and job.stream_name in (
             select btrim(configured.stream_name)
               from unnest(p_oracle_stream_names) as configured(stream_name)
              where btrim(configured.stream_name) <> ''
           )
     order by batch.batch_id
     limit 1
     for update of batch skip locked
  ),
  corrupt as (
    update sync_batches batch
       set status = 'failed',
           last_error = 'sync.oracle.load payload_ref missing and stored payload unavailable',
           updated_at = now()
      from picked
     where batch.batch_id = picked.batch_id
       and nullif(btrim(picked.payload_ref), '') is null
       and picked.stored_payload is null
    returning batch.job_id,
              batch.batch_id,
              batch.last_error
  ),
  error_insert as (
    insert into sync_errors (job_id, batch_id, error_class, error_text)
    select job_id,
           batch_id,
           'dispatch_payload_ref_missing',
           last_error
      from corrupt
    returning id
  ),
  corrupt_job_mark as (
    update sync_jobs job
       set status = 'failed',
           finished_at = now()
      from corrupt
     where job.job_id = corrupt.job_id
    returning job.job_id
  ),
  updated as (
    update sync_batches batch
       set status = 'dispatched',
           attempt_count = batch.attempt_count + 1,
           last_error = null,
           updated_at = now(),
           payload_ref = coalesce(
             nullif(btrim(batch.payload_ref), ''),
             'inline://json/' ||
             rtrim(
               translate(
                 replace(encode(convert_to(picked.stored_payload::text, 'UTF8'), 'base64'), E'\n', ''),
                 '+/',
                 '-_'
               ),
               '='
             )
           )
      from picked
     where batch.batch_id = picked.batch_id
       and not exists (select 1 from corrupt)
       and (
             nullif(btrim(picked.payload_ref), '') is not null
             or picked.stored_payload is not null
           )
    returning batch.batch_id,
              batch.job_id,
              batch.batch_no,
              batch.payload_ref,
              batch.cursor_start,
              batch.cursor_end,
              batch.attempt_count
  ),
  job_mark as (
    update sync_jobs job
       set status = 'running',
           started_at = coalesce(job.started_at, now())
      from updated
     where job.job_id = updated.job_id
    returning job.job_id, job.stream_name
  )
  select jsonb_build_object(
           'job_id', updated.job_id::text,
           'batch_id', updated.batch_id::text,
           'batch_no', updated.batch_no,
           'stream_name', job_mark.stream_name,
           'payload_ref', updated.payload_ref,
           'cursor_start', updated.cursor_start,
           'cursor_end', updated.cursor_end,
           'attempt', updated.attempt_count
         )
    into v_payload
    from updated
    join job_mark on job_mark.job_id = updated.job_id;

  return v_payload;
end;
$$;
