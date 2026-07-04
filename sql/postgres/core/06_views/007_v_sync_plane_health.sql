-- object: v_sync_plane_health
-- folder: views
-- depends_on: sync_events, sync_jobs, sync_batches
create or replace view v_sync_plane_health as
with ingest_status as (
  select
    status,
    count(*)::bigint as row_count
  from sync_events_expanded
  group by status
),
wireless_ingest_status as (
  select
    status,
    count(*)::bigint as row_count
  from sync_events_expanded
  where stream_name = 'wireless.audit'
  group by status
),
ingest_time as (
  select
    count(*) filter (where stream_name = 'wireless.audit' and observed_at >= now() - interval '24 hours')::bigint as wireless_events_24h_count,
    max(observed_at) filter (where stream_name = 'wireless.audit') as wireless_last_observed_at
  from sync_events_expanded
),
batch_status as (
  select
    status,
    count(*)::bigint as row_count
  from sync_batches
  group by status
),
job_batch_rollup as (
  select
    job.job_id,
    job.status as stored_status,
    job.created_at,
    count(batch.batch_id)::bigint as batch_count,
    count(batch.batch_id) filter (where batch.status in ('pending', 'processing', 'dispatched'))::bigint as open_batch_count,
    count(batch.batch_id) filter (where batch.status = 'failed')::bigint as failed_batch_count,
    count(batch.batch_id) filter (where batch.status = 'completed')::bigint as completed_batch_count
  from sync_jobs job
  left join sync_batches batch on batch.job_id = job.job_id
  group by job.job_id, job.status, job.created_at
),
job_effective_status as (
  select
    case
      when open_batch_count > 0 then stored_status
      when failed_batch_count > 0 then 'failed'
      when completed_batch_count > 0 then 'completed'
      when stored_status in ('pending', 'running') and created_at < now() - interval '5 minutes' then 'orphaned'
      else stored_status
    end as effective_status,
    stored_status,
    count(*)::bigint as row_count
  from job_batch_rollup
  group by
    case
      when open_batch_count > 0 then stored_status
      when failed_batch_count > 0 then 'failed'
      when completed_batch_count > 0 then 'completed'
      when stored_status in ('pending', 'running') and created_at < now() - interval '5 minutes' then 'orphaned'
      else stored_status
    end,
    stored_status
),
backlog_status as (
  select
    status,
    count(*)::bigint as row_count
  from sync_backlog
  group by status
),
shadow_status as (
  select
    count(*) filter (where resolved_at is null)::bigint as open_alert_count,
    max(last_occurred_at) filter (where resolved_at is null) as last_open_alert_at
  from wireless_shadow_alerts
)
select
  now() as measured_at,
  coalesce((select wireless_events_24h_count from ingest_time), 0)::bigint as wireless_events_24h_count,
  (select wireless_last_observed_at from ingest_time) as wireless_last_observed_at,
  coalesce((select row_count from wireless_ingest_status where status = 'pending'), 0)::bigint as wireless_ingest_pending_count,
  coalesce((select row_count from wireless_ingest_status where status = 'processing'), 0)::bigint as wireless_ingest_processing_count,
  coalesce((select row_count from wireless_ingest_status where status = 'batched'), 0)::bigint as wireless_ingest_batched_count,
  coalesce((select row_count from wireless_ingest_status where status = 'failed'), 0)::bigint as wireless_ingest_failed_count,
  coalesce((select sum(row_count) from wireless_ingest_status), 0)::bigint as wireless_ingest_total_count,
  coalesce((select row_count from ingest_status where status = 'pending'), 0)::bigint as ingest_pending_count,
  coalesce((select row_count from ingest_status where status = 'processing'), 0)::bigint as ingest_processing_count,
  coalesce((select row_count from ingest_status where status = 'batched'), 0)::bigint as ingest_batched_count,
  coalesce((select row_count from ingest_status where status = 'failed'), 0)::bigint as ingest_failed_count,
  coalesce((select sum(row_count) from ingest_status), 0)::bigint as ingest_total_count,
  coalesce((select row_count from batch_status where status = 'pending'), 0)::bigint as batch_pending_count,
  coalesce((select row_count from batch_status where status = 'processing'), 0)::bigint as batch_processing_count,
  coalesce((select row_count from batch_status where status = 'dispatched'), 0)::bigint as batch_dispatched_count,
  coalesce((select row_count from batch_status where status = 'completed'), 0)::bigint as batch_completed_count,
  coalesce((select row_count from batch_status where status = 'failed'), 0)::bigint as batch_failed_count,
  coalesce((select sum(row_count) from batch_status), 0)::bigint as batch_total_count,
  coalesce((select sum(row_count) from job_effective_status where stored_status = 'pending'), 0)::bigint as job_stored_pending_count,
  coalesce((select sum(row_count) from job_effective_status where stored_status = 'running'), 0)::bigint as job_stored_running_count,
  coalesce((select sum(row_count) from job_effective_status where stored_status = 'completed'), 0)::bigint as job_stored_completed_count,
  coalesce((select sum(row_count) from job_effective_status where stored_status = 'failed'), 0)::bigint as job_stored_failed_count,
  coalesce((select sum(row_count) from job_effective_status), 0)::bigint as job_total_count,
  coalesce((select sum(row_count) from job_effective_status where effective_status = 'pending'), 0)::bigint as job_effective_pending_count,
  coalesce((select sum(row_count) from job_effective_status where effective_status = 'running'), 0)::bigint as job_effective_running_count,
  coalesce((select sum(row_count) from job_effective_status where effective_status = 'completed'), 0)::bigint as job_effective_completed_count,
  coalesce((select sum(row_count) from job_effective_status where effective_status = 'failed'), 0)::bigint as job_effective_failed_count,
  coalesce((select sum(row_count) from job_effective_status where effective_status = 'orphaned'), 0)::bigint as job_orphaned_count,
  coalesce((select row_count from backlog_status where status = 'pending'), 0)::bigint as backlog_pending_count,
  coalesce((select sum(row_count) from backlog_status where status in ('sync_failed', 'failed')), 0)::bigint as backlog_failed_count,
  coalesce((select open_alert_count from shadow_status), 0)::bigint as open_shadow_it_alert_count,
  (select last_open_alert_at from shadow_status) as last_shadow_it_alert_at,
  (select cursor_value from sync_cursors where stream_name = 'wireless.audit') as wireless_cursor_value,
  (select updated_at from sync_cursors where stream_name = 'wireless.audit') as wireless_cursor_updated_at;
