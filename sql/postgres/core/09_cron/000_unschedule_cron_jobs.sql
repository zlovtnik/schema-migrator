-- object: unschedule_cron_jobs
-- folder: cron
-- depends_on: extensions
-- Unschedules this app's pg_cron jobs left from a previous coordinator lifecycle.
-- Run this BEFORE materialized view DDL so that pg_cron does not
-- terminate the connection when a stale REFRESH job fires during DDL.
--
-- This is idempotent: unscheduling a nonexistent job is a no-op.

do $$
declare
  j record;
begin
  if to_regnamespace('cron') is not null then
    for j in
      select jobid
      from cron.job
      where jobname like 'vec-%'
         or jobname like 'search-%'
         or jobname like 'sync-%'
         or jobname = 'sync-event-retention-prune'
      order by jobid
    loop
      perform cron.unschedule(j.jobid);
    end loop;
  end if;
end $$;
