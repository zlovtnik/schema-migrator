-- object: v_bssid_anomaly_score
-- folder: views
-- depends_on: vec_behaviour_snapshots, vec_baseline_profiles
create or replace view v_bssid_anomaly_score as
with current_base as (
  select
    lower(nullif(coalesce(bssid, payload->>'bssid', destination_bssid, payload->>'destination_bssid'), '')) as bssid,
    coalesce(signal_dbm,
      case when payload->>'signal_dbm' ~ '^-?[0-9]+$' then (payload->>'signal_dbm')::integer end
    ) as signal_dbm,
    coalesce(retry, false) as retry,
    coalesce(channel_number::text, payload->>'channel_number', payload->>'channel') as channel_number,
    coalesce(frame_subtype, payload->>'frame_subtype') as frame_subtype,
    lower(nullif(coalesce(source_mac, payload->>'source_mac'), '')) as source_mac,
    observed_at,
    date_bin(interval '15 minutes', observed_at, timestamptz '2000-01-01 00:00:00+00') as window_start
  from sync_events_expanded
  where stream_name = 'wireless.audit'
    and observed_at >= now() - interval '1 hour'
    and nullif(coalesce(bssid, payload->>'bssid', destination_bssid, payload->>'destination_bssid'), '') is not null
),
current_metrics as (
  select bssid, 'beacon_interval_ms' as metric, percentile_cont(0.5) within group (order by interval_ms) as observed_metric
  from (
    select
      bssid,
      extract(epoch from observed_at - lag(observed_at) over (partition by bssid order by observed_at)) * 1000.0 as interval_ms
    from current_base
    where frame_subtype = 'beacon'
  ) beacon_intervals
  where interval_ms is not null and interval_ms > 0
  group by bssid
  union all
  select bssid, 'retry_rate' as metric, percentile_cont(0.5) within group (order by retry_rate) as observed_metric
  from (
    select bssid, window_start, avg((retry::int)::numeric) as retry_rate
    from current_base
    group by bssid, window_start
  ) retry_window
  group by bssid
  union all
  select bssid, 'signal_iqr_dbm' as metric, percentile_cont(0.5) within group (order by q75 - q25) as observed_metric
  from (
    select bssid, window_start,
      percentile_cont(0.25) within group (order by signal_dbm) as q25,
      percentile_cont(0.75) within group (order by signal_dbm) as q75
    from current_base
    where signal_dbm is not null
    group by bssid, window_start
  ) signal_window
  where q25 is not null and q75 is not null
  group by bssid
  union all
  select bssid, 'channel_dwell_ratio' as metric, percentile_cont(0.5) within group (order by top_channel_share) as observed_metric
  from (
    select
      bssid,
      window_start,
      max(channel_share) as top_channel_share
    from (
      select
        bssid,
        window_start,
        channel_count::numeric / sum(channel_count) over (partition by bssid, window_start) as channel_share
      from (
        select bssid, window_start, channel_number, count(*)::bigint as channel_count
        from current_base
        where channel_number is not null
        group by bssid, window_start, channel_number
      ) channel_window
    ) sub
    group by bssid, window_start
  ) channel_dwell
  group by bssid
  union all
  select bssid, 'association_timing_secs' as metric, percentile_cont(0.5) within group (order by delta_secs) as observed_metric
  from (
    select
      bssid,
      extract(epoch from observed_at - lag(observed_at) over (partition by bssid, source_mac order by observed_at)) as delta_secs
    from current_base
    where frame_subtype in ('association_request', 'reassociation_request')
      and source_mac is not null
  ) assoc_deltas
  where delta_secs is not null and delta_secs >= 0
  group by bssid
)
select
  cm.bssid,
  cm.metric,
  cm.observed_metric,
  bp.p5,
  bp.p50,
  bp.p95,
  case when bp.p95 > bp.p5 then (cm.observed_metric - bp.p50) / nullif(bp.p95 - bp.p5, 0) else null end as anomaly_score
from current_metrics cm
join vec_baseline_profiles bp
  on bp.bssid = cm.bssid
  and bp.metric = cm.metric;
