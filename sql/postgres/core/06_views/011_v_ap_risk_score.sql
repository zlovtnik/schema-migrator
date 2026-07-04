-- object: v_ap_risk_score
-- folder: views
-- depends_on: vec_alerts, vec_similarity_pairs
-- Track 6.1: Composite AP risk score combining deauth, signal, typosquat,
-- vendor mismatch, and embedding outlier signals into a single score.
create or replace view v_ap_risk_score as
with alert_bssid_scores as (
  select
    alert_bssid.bssid,
    alert.alert_type,
    alert.metadata,
    alert.score
  from vec_alerts alert
  cross join lateral (
    select lower(nullif(trim(alert.metadata->>'bssid'), '')) as bssid
    union
    select lower(nullif(trim(alert.metadata->>'destination_bssid'), '')) as bssid
    union
    select lower(nullif(trim(value), '')) as bssid
    from jsonb_array_elements_text(
      case
        when jsonb_typeof(alert.metadata->'bssids') = 'array' then alert.metadata->'bssids'
        else '[]'::jsonb
      end
    ) as bssid_values(value)
  ) alert_bssid
  where alert_bssid.bssid is not null
    and alert.created_at >= now() - interval '1 hour'
),
deauth_scores as (
  select
    bssid,
    max(score) as deauth_score
  from alert_bssid_scores
  where alert_type in ('rogue_cluster', 'deauth_flood')
  group by bssid
),
signal_anomaly_scores as (
  select
    bssid,
    max(score) as signal_anomaly_score
  from alert_bssid_scores
  where alert_type in ('signal_anomaly', 'rogue_cluster')
    and metadata->>'reason' in ('signal_jump', 'channel_band_conflict')
  group by bssid
),
typosquat_scores as (
  select
    bssid,
    max(score) as typosquat_score
  from alert_bssid_scores
  where alert_type = 'rogue_cluster'
    and metadata->>'reason' in ('ssid_typosquat', 'vendor_conflict', 'bssid_spoofing')
  group by bssid
),
vendor_mismatch_scores as (
  select
    lower(substr(regexp_replace(coalesce(nullif(bssid, ''), nullif(payload->>'bssid', ''), nullif(destination_bssid, ''), nullif(payload->>'destination_bssid', '')), '[:\-]', '', 'g'), 1, 6)) as bssid_oui,
    count(distinct lower(substr(regexp_replace(bssid, '[:\-]', '', 'g'), 1, 6)))::double precision as vendor_mismatch_score
  from sync_events_expanded
  where stream_name = 'wireless.audit'
    and observed_at >= now() - interval '1 hour'
    and nullif(coalesce(ssid, payload->>'ssid'), '') is not null
    and coalesce(nullif(bssid, ''), nullif(payload->>'bssid', ''), nullif(destination_bssid, ''), nullif(payload->>'destination_bssid', '')) is not null
    and lower(substr(regexp_replace(coalesce(nullif(bssid, ''), nullif(payload->>'bssid', ''), nullif(destination_bssid, ''), nullif(payload->>'destination_bssid', '')), '[:\-]', '', 'g'), 1, 6)) is not null
  group by bssid_oui
),
similarity_bssid_rows as (
  select
    lower(nullif(trim(coalesce(nullif(left_event.bssid, ''), nullif(left_event.payload->>'bssid', ''), nullif(left_event.destination_bssid, ''), nullif(left_event.payload->>'destination_bssid', ''))), '')) as bssid,
    p.cosine_distance
  from vec_similarity_pairs p
  join sync_events_expanded left_event
    on p.left_source_table = 'sync_events'
   and left_event.dedupe_key = p.left_source_key
  where p.computed_at >= now() - interval '1 hour'
    and p.cosine_distance > 0.15
  union all
  select
    lower(nullif(trim(coalesce(nullif(right_event.bssid, ''), nullif(right_event.payload->>'bssid', ''), nullif(right_event.destination_bssid, ''), nullif(right_event.payload->>'destination_bssid', ''))), '')) as bssid,
    p.cosine_distance
  from vec_similarity_pairs p
  join sync_events_expanded right_event
    on p.right_source_table = 'sync_events'
   and right_event.dedupe_key = p.right_source_key
  where p.computed_at >= now() - interval '1 hour'
    and p.cosine_distance > 0.15
),
embedding_outlier_scores as (
  select
    bssid,
    max(cosine_distance) as embedding_outlier_score
  from similarity_bssid_rows
  where bssid is not null
  group by bssid
),
baseline_deviation_scores as (
  select
    bssid,
    max(abs(anomaly_score)) as baseline_deviation_score
  from v_bssid_anomaly_score
  where anomaly_score is not null
    and abs(anomaly_score) > 2.0
  group by bssid
),
all_bssids as (
  select distinct bssid
  from alert_bssid_scores
  union
  select distinct bssid
  from similarity_bssid_rows
  where bssid is not null
  union
  select distinct bssid
  from baseline_deviation_scores
)
select
  a.bssid,
  coalesce(d.deauth_score, 0::double precision) as deauth_score,
  coalesce(s.signal_anomaly_score, 0::double precision) as signal_anomaly_score,
  coalesce(t.typosquat_score, 0::double precision) as typosquat_score,
  coalesce(v.vendor_mismatch_score, 0::double precision) as vendor_mismatch_score,
  coalesce(e.embedding_outlier_score, 0::double precision) as embedding_outlier_score,
  coalesce(b.baseline_deviation_score, 0::double precision) as baseline_deviation_score,
  (coalesce(d.deauth_score, 0::double precision) * 0.20
   + coalesce(s.signal_anomaly_score, 0::double precision) * 0.15
   + coalesce(t.typosquat_score, 0::double precision) * 0.15
   + coalesce(v.vendor_mismatch_score, 0::double precision) * 0.15
   + coalesce(e.embedding_outlier_score, 0::double precision) * 0.15
   + coalesce(b.baseline_deviation_score, 0::double precision) * 0.20) as composite_risk
from all_bssids a
left join deauth_scores d on d.bssid = a.bssid
left join signal_anomaly_scores s on s.bssid = a.bssid
left join typosquat_scores t on t.bssid = a.bssid
left join vendor_mismatch_scores v on v.bssid_oui = lower(substr(regexp_replace(a.bssid, '[:\-]', '', 'g'), 1, 6))
left join embedding_outlier_scores e on e.bssid = a.bssid
left join baseline_deviation_scores b on b.bssid = a.bssid;
