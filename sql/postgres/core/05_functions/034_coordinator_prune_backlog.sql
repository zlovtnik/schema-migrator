-- object: coordinator.prune_backlog
-- folder: functions
-- depends_on: sync_backlog
create or replace function coordinator.prune_backlog()
returns integer
language plpgsql
as $$
declare
  v_deleted integer;
begin
  delete from sync_backlog
  where status = 'synced'
    and updated_at < now() - interval '7 days';
  get diagnostics v_deleted = row_count;
  return v_deleted;
end;
$$;
