-- object: coordinator.mark_backlog_synced
-- folder: functions
-- depends_on: sync_backlog
create or replace function coordinator.mark_backlog_synced(p_dedupe_key text)
returns void
language sql
as $$
  update sync_backlog
  set status = 'synced', updated_at = now()
  where dedupe_key = p_dedupe_key;
$$;
