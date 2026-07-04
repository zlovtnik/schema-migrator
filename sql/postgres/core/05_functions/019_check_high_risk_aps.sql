-- object: check_high_risk_aps
-- folder: functions
-- depends_on: mv_ap_risk_score
-- Check for high-risk APs and insert alerts when composite_risk exceeds threshold
create or replace function check_high_risk_aps(p_threshold double precision default 0.75)
returns integer
language plpgsql
as $$
declare
  v_count integer;
begin
  insert into vec_alerts (alert_type, source_mac, score, explanation_text, metadata)
  select
    'high_risk_ap'::text,
    scored.bssid,
    scored.composite_risk,
    concat(
      'High-risk AP ', scored.bssid, ': composite_risk=',
      round(scored.composite_risk::numeric, 2), ' exceeded threshold ',
      round(p_threshold::numeric, 2), '. ',
      'Dominant signal: ', scored.dominant_signal, '. ',
      'Contributing factors: deauth_score=',
      round(scored.deauth_score::numeric, 2),
      ', signal_anomaly_score=',
      round(scored.signal_anomaly_score::numeric, 2),
      ', typosquat_score=',
      round(scored.typosquat_score::numeric, 2),
      ', vendor_mismatch_score=',
      round(scored.vendor_mismatch_score::numeric, 2),
      ', embedding_outlier_score=',
      round(scored.embedding_outlier_score::numeric, 2),
      ', baseline_deviation_score=',
      round(scored.baseline_deviation_score::numeric, 2), '.'
    ),
    jsonb_build_object(
      'composite_risk', scored.composite_risk,
      'deauth_score', scored.deauth_score,
      'signal_anomaly_score', scored.signal_anomaly_score,
      'typosquat_score', scored.typosquat_score,
      'vendor_mismatch_score', scored.vendor_mismatch_score,
      'embedding_outlier_score', scored.embedding_outlier_score,
      'baseline_deviation_score', scored.baseline_deviation_score,
      'dominant_signal', scored.dominant_signal
    )
  from (
    select
      r.*,
      case
        when r.deauth_score >= greatest(r.signal_anomaly_score, r.typosquat_score, r.vendor_mismatch_score, r.embedding_outlier_score, r.baseline_deviation_score)
          and r.deauth_score > 0 then 'deauth_storm'
        when r.baseline_deviation_score >= greatest(r.deauth_score, r.signal_anomaly_score, r.typosquat_score, r.vendor_mismatch_score, r.embedding_outlier_score)
          and r.baseline_deviation_score > 0 then 'baseline_deviation'
        when r.typosquat_score > 0 then 'typosquatting'
        when r.embedding_outlier_score > 0 then 'embedding_outlier'
        when r.signal_anomaly_score > 0 then 'signal_anomaly'
        when r.vendor_mismatch_score > 0 then 'vendor_mismatch'
        else 'composite_risk'
      end as dominant_signal
    from mv_ap_risk_score r
  ) scored
  where scored.composite_risk > p_threshold
    and not exists (
      select 1 from vec_alerts a
      where a.alert_type = 'high_risk_ap'
        and a.source_mac is not distinct from scored.bssid
        and a.created_at > now() - interval '1 hour'
    );

  get diagnostics v_count = row_count;
  return v_count;
end;
$$;
