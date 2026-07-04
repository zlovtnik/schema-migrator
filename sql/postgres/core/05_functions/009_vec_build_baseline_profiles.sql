-- object: vec_build_baseline_profiles
-- folder: functions
-- depends_on: vec_baseline_profiles, vec_behaviour_snapshots
drop function if exists vec_build_baseline_profiles(timestamptz, timestamptz);

create or replace function vec_build_baseline_profiles(
  p_from timestamptz default now() - interval '7 days',
  p_to timestamptz default now(),
  p_window interval default interval '15 minutes'
)
returns integer
language plpgsql
as $$
declare
  v_count integer := 0;
begin
  if not vec_try_begin_job('vec_build_baseline_profiles') then
    return 0;
  end if;

  with base as (
    select
      lower(nullif(coalesce(bssid, payload->>'bssid', destination_bssid, payload->>'destination_bssid'), '')) as bssid,
      observed_at,
      coalesce(signal_dbm,
        case when payload->>'signal_dbm' ~ '^-?[0-9]+$' then (payload->>'signal_dbm')::integer end
      ) as signal_dbm,
      coalesce(retry, false) as retry,
      coalesce(channel_number::text, payload->>'channel_number', payload->>'channel') as channel_number,
      coalesce(frame_subtype, payload->>'frame_subtype') as frame_subtype,
      lower(nullif(coalesce(source_mac, payload->>'source_mac'), '')) as source_mac
    from sync_events_expanded
    where stream_name = 'wireless.audit'
      and status = 'batched'
      and observed_at >= p_from
      and observed_at < p_to
      and nullif(coalesce(bssid, payload->>'bssid', destination_bssid, payload->>'destination_bssid'), '') is not null
  ),
  beacon_intervals as (
    select
      bssid,
      extract(epoch from observed_at - lag(observed_at) over (partition by bssid order by observed_at)) * 1000.0 as interval_ms
    from base
    where frame_subtype = 'beacon'
  ),
  beacon_metrics as (
    select
      bssid,
      'beacon_interval_ms'::text as metric,
      percentile_cont(0.05) within group (order by interval_ms) as p5,
      percentile_cont(0.5) within group (order by interval_ms) as p50,
      percentile_cont(0.95) within group (order by interval_ms) as p95,
      count(*) as sample_count
    from beacon_intervals
    where interval_ms is not null and interval_ms > 0
    group by bssid
  ),
  retry_window as (
    select
      bssid,
      date_bin(p_window, observed_at, timestamptz '2000-01-01 00:00:00+00') as window_start,
      avg((retry::int)::numeric) as retry_rate
    from base
    group by bssid, window_start
  ),
  retry_metrics as (
    select
      bssid,
      'retry_rate'::text as metric,
      percentile_cont(0.05) within group (order by retry_rate) as p5,
      percentile_cont(0.5) within group (order by retry_rate) as p50,
      percentile_cont(0.95) within group (order by retry_rate) as p95,
      count(*) as sample_count
    from retry_window
    group by bssid
  ),
  signal_window as (
    select
      bssid,
      date_bin(p_window, observed_at, timestamptz '2000-01-01 00:00:00+00') as window_start,
      percentile_cont(0.25) within group (order by signal_dbm) as q25,
      percentile_cont(0.75) within group (order by signal_dbm) as q75
    from base
    where signal_dbm is not null
    group by bssid, window_start
  ),
  signal_metrics as (
    select
      bssid,
      'signal_iqr_dbm'::text as metric,
      percentile_cont(0.05) within group (order by q75 - q25) as p5,
      percentile_cont(0.5) within group (order by q75 - q25) as p50,
      percentile_cont(0.95) within group (order by q75 - q25) as p95,
      count(*) as sample_count
    from signal_window
    where q25 is not null and q75 is not null
    group by bssid
  ),
  channel_window as (
    select
      bssid,
      date_bin(p_window, observed_at, timestamptz '2000-01-01 00:00:00+00') as window_start,
      channel_number,
      count(*)::bigint as channel_count
    from base
    where channel_number is not null
    group by bssid, window_start, channel_number
  ),
  channel_dwell as (
    select
      bssid,
      window_start,
      max(channel_share) as top_channel_share
    from (
      select
        bssid,
        window_start,
        channel_count::numeric / sum(channel_count) over (partition by bssid, window_start) as channel_share
      from channel_window
    ) sub
    group by bssid, window_start
  ),
  channel_metrics as (
    select
      bssid,
      'channel_dwell_ratio'::text as metric,
      percentile_cont(0.05) within group (order by top_channel_share) as p5,
      percentile_cont(0.5) within group (order by top_channel_share) as p50,
      percentile_cont(0.95) within group (order by top_channel_share) as p95,
      count(*) as sample_count
    from channel_dwell
    group by bssid
  ),
  assoc_deltas as (
    select
      bssid,
      extract(epoch from observed_at - lag(observed_at) over (partition by bssid, source_mac order by observed_at)) as delta_secs
    from base
    where frame_subtype in ('association_request', 'reassociation_request')
      and source_mac is not null
  ),
  assoc_metrics as (
    select
      bssid,
      'association_timing_secs'::text as metric,
      percentile_cont(0.05) within group (order by delta_secs) as p5,
      percentile_cont(0.5) within group (order by delta_secs) as p50,
      percentile_cont(0.95) within group (order by delta_secs) as p95,
      count(*) as sample_count
    from assoc_deltas
    where delta_secs is not null and delta_secs >= 0
    group by bssid
  ),
  metrics as (
    select * from beacon_metrics
    union all
    select * from retry_metrics
    union all
    select * from signal_metrics
    union all
    select * from channel_metrics
    union all
    select * from assoc_metrics
  )
  insert into vec_baseline_profiles (
    bssid, metric, p5, p50, p95, sample_count, created_at, updated_at
  )
  select
    bssid, metric, p5, p50, p95, sample_count, now(), now()
  from metrics
  on conflict (bssid, metric) do update set
    p5 = excluded.p5,
    p50 = excluded.p50,
    p95 = excluded.p95,
    sample_count = excluded.sample_count,
    updated_at = now();

  get diagnostics v_count = row_count;
  perform vec_finish_job('vec_build_baseline_profiles');
  return v_count;
exception when others then
  perform vec_finish_job('vec_build_baseline_profiles');
  raise;
end;
$$;
