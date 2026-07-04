-- object: coordinator.pending_ledger_count
-- folder: functions
-- depends_on: sync_events
-- Backpressure: count events waiting in the ingest pipeline (pending + processing).
-- Called every iteration to decide whether to pull more from Redpanda.
create or replace function coordinator.pending_ledger_count()
returns bigint
language sql stable
as $$
  select count(*)::bigint from sync_events where status in ('pending', 'processing');
$$;
