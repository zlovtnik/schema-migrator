-- object: coordinator.recover_stale_dispatched_batches
-- folder: functions
-- depends_on: sync_batches, sync_jobs
drop function if exists coordinator.get_next_batch();

create or replace function coordinator.recover_stale_dispatched_batches(
  p_oracle_stream_names text[],
  p_dispatch_lease_seconds integer,
  p_max_attempts integer
)
returns integer
language plpgsql
as $$
declare
  v_recovered_count integer := 0;
  v_lease_seconds integer := greatest(coalesce(p_dispatch_lease_seconds, 300), 1);
  v_max_attempts integer := greatest(coalesce(p_max_attempts, 5), 1);
begin
  with stale_dispatched as (
    select batch.batch_id
      from sync_batches batch
      join sync_jobs job on job.job_id = batch.job_id
     where batch.status = 'dispatched'
       and batch.updated_at < now() - make_interval(secs => v_lease_seconds)
       and job.stream_name in (
             select btrim(configured.stream_name)
               from unnest(p_oracle_stream_names) as configured(stream_name)
              where btrim(configured.stream_name) <> ''
           )
     order by batch.updated_at asc
     for update skip locked
  ),
  failed_dispatch as (
    select batch.batch_id
      from sync_batches batch
      join sync_jobs job on job.job_id = batch.job_id
     where batch.status = 'failed'
       and (
             batch.last_error like 'sync.oracle.load publish failed:%'
             or batch.last_error like 'sync.oracle.load dispatch lease expired%'
           )
       and job.stream_name in (
             select btrim(configured.stream_name)
               from unnest(p_oracle_stream_names) as configured(stream_name)
              where btrim(configured.stream_name) <> ''
           )
     order by batch.updated_at asc
     for update skip locked
  ),
  stale_recovered as (
    update sync_batches batch
       set status = case
                     when batch.attempt_count >= v_max_attempts then 'failed'
                     else 'pending'
                   end,
           last_error = case
                         when batch.attempt_count >= v_max_attempts
                         then 'sync.oracle.load dispatch lease expired'
                         else 'sync.oracle.load dispatch lease expired; retrying'
                       end,
           updated_at = now()
      from stale_dispatched
     where batch.batch_id = stale_dispatched.batch_id
    returning batch.job_id,
              batch.batch_id,
              batch.status,
              batch.last_error
  ),
  failed_dispatch_recovered as (
    update sync_batches batch
       set status = 'pending',
           last_error = 'sync.oracle.load dispatch failure recovered; retrying',
           updated_at = now()
      from failed_dispatch
     where batch.batch_id = failed_dispatch.batch_id
       and batch.attempt_count < v_max_attempts
    returning batch.job_id,
              batch.batch_id,
              batch.status,
              batch.last_error
  ),
  recovered as (
    select * from stale_recovered
    union all
    select * from failed_dispatch_recovered
  ),
  error_insert as (
    insert into sync_errors (job_id, batch_id, error_class, error_text)
    select job_id,
           batch_id,
           'dispatch_lease_expired',
           coalesce(last_error, 'sync.oracle.load dispatch lease expired')
      from recovered
     where status = 'failed'
    returning id
  ),
  job_update as (
    update sync_jobs job
       set status = case
                     when exists (
                       select 1
                         from recovered
                        where recovered.job_id = job.job_id
                          and recovered.status = 'failed'
                     ) then 'failed'
                     else 'pending'
                   end,
           finished_at = case
                           when exists (
                             select 1
                               from recovered
                              where recovered.job_id = job.job_id
                                and recovered.status = 'failed'
                           ) then now()
                           else null
                         end
     where job.job_id in (select job_id from recovered)
    returning job.job_id
  )
  select count(*) into v_recovered_count from recovered;

  return coalesce(v_recovered_count, 0);
end;
$$;
