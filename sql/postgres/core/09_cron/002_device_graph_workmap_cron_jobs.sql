-- object: device_graph_workmap_cron_jobs
-- folder: cron
-- depends_on: extensions, vec_device_graph_retention, vec_job_lock_ttl_helpers, vec_dns_violation_summary

create or replace function vec_refresh_dns_violation_summary()
returns void
language plpgsql
as $$
begin
  if not vec_try_begin_maintenance_job('vec-refresh-dns-violation-summary') then
    return;
  end if;

  refresh materialized view vec_dns_violation_summary;

  perform vec_finish_maintenance_job('vec-refresh-dns-violation-summary');
exception when others then
  perform vec_finish_maintenance_job('vec-refresh-dns-violation-summary');
  raise;
end;
$$;

create or replace function vec_install_device_graph_cron_jobs()
returns void
language plpgsql
as $$
declare
  v_job text;
begin
  if to_regnamespace('cron') is null then
    raise exception 'pg_cron schema is unavailable';
  end if;

  foreach v_job in array array[
    'vec-prune-device-graph-retention',
    'vec-prune-stale-job-locks',
    'vec-refresh-dns-violation-summary'
  ] loop
    if exists (select 1 from cron.job where jobname = v_job) then
      perform cron.unschedule(v_job);
    end if;
  end loop;

  perform cron.schedule(
    'vec-prune-device-graph-retention',
    '27 2 * * *',
    $cron$select vec_run_maintenance_sql('vec-prune-device-graph-retention', $stmt$select vec_prune_device_graph_retention()$stmt$);$cron$
  );

  perform cron.schedule(
    'vec-prune-stale-job-locks',
    '*/5 * * * *',
    $cron$select vec_prune_stale_job_locks();$cron$
  );

  perform cron.schedule(
    'vec-refresh-dns-violation-summary',
    '41 2 * * *',
    $cron$select vec_refresh_dns_violation_summary();$cron$
  );
end;
$$;

do $$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform vec_install_device_graph_cron_jobs();
  else
    raise notice 'pg_cron extension unavailable; skipping device graph cron job installation';
  end if;
end $$;
