-- object: vec_reembed_on_alert
-- folder: functions
-- depends_on: vec_alerts, vec_embedding_jobs
create or replace function vec_reembed_on_alert()
returns trigger
language plpgsql
as $$
begin
  if new.alert_type = 'high_risk_ap' and new.source_mac is not null then
    with jobs_reset as (
      update vec_embedding_jobs
         set status = 'pending',
             content_sha256 = null,
             updated_at = now()
       where source_table = 'vec_baseline_profiles'
         and source_key = lower(new.source_mac)
         and status = 'completed'
      returning job_id
    )
    update vec_embedding_job_leases lease
       set due_at = now(),
           attempts = 0,
           completed_at = null,
           lease_token = null,
           leased_at = null,
           locked_by = null,
           last_error = null
      from jobs_reset
     where lease.job_id = jobs_reset.job_id;
  end if;

  if new.alert_type = 'embedding_drift' and new.source_mac is not null then
    with jobs_reset as (
      update vec_embedding_jobs
         set status = 'pending',
             content_sha256 = null,
             updated_at = now()
       where source_table = 'sync_events'
         and status = 'completed'
         and source_key in (
           select dedupe_key
           from sync_events_expanded
           where lower(coalesce(source_mac, payload->>'source_mac')) = lower(new.source_mac)
             and observed_at >= now() - interval '2 hours'
         )
      returning job_id
    )
    update vec_embedding_job_leases lease
       set due_at = now(),
           attempts = 0,
           completed_at = null,
           lease_token = null,
           leased_at = null,
           locked_by = null,
           last_error = null
      from jobs_reset
     where lease.job_id = jobs_reset.job_id;
  end if;

  return new;
end;
$$;

drop trigger if exists vec_alert_reembed on vec_alerts;

create trigger vec_alert_reembed
after insert on vec_alerts
for each row execute function vec_reembed_on_alert();
