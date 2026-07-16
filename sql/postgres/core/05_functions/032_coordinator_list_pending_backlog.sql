-- object: coordinator.list_pending_backlog
-- folder: functions
-- depends_on: sync_backlog
create or replace function coordinator.list_pending_backlog()
returns jsonb
language sql
as $$
  select coalesce(jsonb_agg(
    jsonb_build_object(
      'dedupe_key', dedupe_key,
      'stream_name', stream_name,
      'payload', payload,
      'failure_stage', failure_stage,
      'attempt_count', attempt_count,
      'created_at', created_at
    )
  ), '[]'::jsonb)
  from (
    select dedupe_key, stream_name, payload, failure_stage, attempt_count, created_at
    from sync_backlog
    where status = 'pending'
    order by created_at asc
    limit 100
  ) pending;
$$;
