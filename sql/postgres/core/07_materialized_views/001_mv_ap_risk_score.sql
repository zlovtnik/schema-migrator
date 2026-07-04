-- object: mv_ap_risk_score
-- folder: materialized_views
-- depends_on: v_ap_risk_score
-- Materialized for 5-minute refresh alongside v_device_repetition_score
do $$
declare
  j record;
begin
  if to_regnamespace('cron') is not null then
    for j in select jobname from cron.job where jobname like 'vec-%' loop
      perform cron.unschedule(j.jobname);
    end loop;
  end if;
end $$;

drop materialized view if exists mv_ap_risk_score;

create materialized view mv_ap_risk_score as
select * from v_ap_risk_score;

create unique index if not exists idx_mv_ap_risk_score_bssid
  on mv_ap_risk_score (bssid);
