-- object: vec_build_timing_profiles
-- folder: functions
-- depends_on: vec_timing_profiles
create or replace function vec_build_timing_profiles(
  p_from timestamptz default now() - interval '2 hours',
  p_to timestamptz default now(),
  p_window interval default interval '15 minutes'
)
returns integer
language plpgsql
as $$
declare
  v_count integer := 0;
begin
  if not vec_try_begin_job('vec_build_timing_profiles') then
    return 0;
  end if;

  with base as (
    select
      lower(nullif(coalesce(sse.source_mac, sse.payload->>'source_mac'), '')) as source_mac,
      nullif(coalesce(sse.sensor_id, sse.payload->>'sensor_id'), '') as sensor_id,
      nullif(coalesce(sse.location_id, sse.payload->>'location_id'), '') as location_id,
      date_bin(p_window, sse.observed_at, timestamptz '2000-01-01 00:00:00+00') as window_start,
      coalesce(sse.tsft_delta_us, timeline.tsft_delta_us) as tsft_delta_us,
      coalesce(sse.wall_clock_delta_ms, timeline.wall_clock_delta_ms) as wall_clock_delta_ms,
      coalesce(sse.frame_subtype, sse.payload->>'frame_subtype') as frame_subtype
    from sync_events_expanded sse
    left join v_wireless_session_timeline timeline
      on timeline.dedupe_key = sse.dedupe_key
    where sse.stream_name = 'wireless.audit'
      and sse.status = 'batched'
      and sse.observed_at >= p_from
      and sse.observed_at < p_to
      and nullif(coalesce(sse.source_mac, sse.payload->>'source_mac'), '') is not null
      and coalesce(sse.tsft_delta_us, timeline.tsft_delta_us) is not null
      and coalesce(sse.tsft_delta_us, timeline.tsft_delta_us) >= 0
  ),
  stats as (
    select
      source_mac,
      sensor_id,
      location_id,
      window_start,
      window_start + p_window as window_end,
      percentile_cont(0.50) within group (order by tsft_delta_us) as tsft_p50_us,
      percentile_cont(0.95) within group (order by tsft_delta_us) as tsft_p95_us,
      percentile_cont(0.75) within group (order by tsft_delta_us)
        - percentile_cont(0.25) within group (order by tsft_delta_us) as tsft_jitter,
      percentile_cont(0.50) within group (order by wall_clock_delta_ms) as wall_p50_ms,
      percentile_cont(0.75) within group (order by wall_clock_delta_ms)
        - percentile_cont(0.25) within group (order by wall_clock_delta_ms) as wall_jitter_ms
    from base
    group by source_mac, sensor_id, location_id, window_start
    having count(*) >= 5
  ),
  beacon_stats as (
    select
      source_mac,
      sensor_id,
      location_id,
      window_start,
      percentile_cont(0.50) within group (order by tsft_delta_us / 1000.0) as beacon_interval_median_ms,
      percentile_cont(0.75) within group (order by tsft_delta_us / 1000.0)
        - percentile_cont(0.25) within group (order by tsft_delta_us / 1000.0) as beacon_jitter_ms
    from base
    where frame_subtype ilike '%beacon%'
    group by source_mac, sensor_id, location_id, window_start
  ),
  prepared as (
    select
      md5(
        s.source_mac || '|' ||
        coalesce(s.sensor_id, '') || '|' ||
        coalesce(s.location_id, '') || '|' ||
        s.window_start::text || '|' ||
        s.window_end::text
      ) as profile_key,
      s.source_mac,
      s.sensor_id,
      s.location_id,
      s.window_start,
      s.window_end,
      s.tsft_p50_us,
      s.tsft_p95_us,
      s.tsft_jitter,
      s.wall_p50_ms,
      s.wall_jitter_ms,
      b.beacon_interval_median_ms,
      b.beacon_jitter_ms,
      concat_ws(
        E'\n',
        'kind: timing_profile',
        'tsft_p50_us: ' || round(coalesce(s.tsft_p50_us, 0)::numeric, 1),
        'tsft_p95_us: ' || round(coalesce(s.tsft_p95_us, 0)::numeric, 1),
        'tsft_jitter: ' || round(coalesce(s.tsft_jitter, 0)::numeric, 1),
        'wall_p50_ms: ' || round(coalesce(s.wall_p50_ms, 0)::numeric, 2),
        'wall_jitter_ms: ' || round(coalesce(s.wall_jitter_ms, 0)::numeric, 2),
        'beacon_interval_ms: ' || round(coalesce(b.beacon_interval_median_ms, 0)::numeric, 2),
        'beacon_jitter_ms: ' || round(coalesce(b.beacon_jitter_ms, 0)::numeric, 2)
      ) as embedding_text
    from stats s
    left join beacon_stats b
      on b.source_mac = s.source_mac
     and b.sensor_id is not distinct from s.sensor_id
     and b.location_id is not distinct from s.location_id
     and b.window_start = s.window_start
  )
  insert into vec_timing_profiles (
    profile_key,
    source_mac,
    sensor_id,
    location_id,
    window_start,
    window_end,
    tsft_p50_us,
    tsft_p95_us,
    tsft_jitter,
    wall_p50_ms,
    wall_jitter_ms,
    beacon_interval_median_ms,
    beacon_jitter_ms,
    embedding_text,
    created_at,
    updated_at
  )
  select
    profile_key,
    source_mac,
    sensor_id,
    location_id,
    window_start,
    window_end,
    tsft_p50_us,
    tsft_p95_us,
    tsft_jitter,
    wall_p50_ms,
    wall_jitter_ms,
    beacon_interval_median_ms,
    beacon_jitter_ms,
    embedding_text,
    now(),
    now()
  from prepared
  on conflict (profile_key) do update set
    source_mac = excluded.source_mac,
    sensor_id = excluded.sensor_id,
    location_id = excluded.location_id,
    window_start = excluded.window_start,
    window_end = excluded.window_end,
    tsft_p50_us = excluded.tsft_p50_us,
    tsft_p95_us = excluded.tsft_p95_us,
    tsft_jitter = excluded.tsft_jitter,
    wall_p50_ms = excluded.wall_p50_ms,
    wall_jitter_ms = excluded.wall_jitter_ms,
    beacon_interval_median_ms = excluded.beacon_interval_median_ms,
    beacon_jitter_ms = excluded.beacon_jitter_ms,
    embedding_text = excluded.embedding_text,
    updated_at = now();

  get diagnostics v_count = row_count;
  perform vec_finish_job('vec_build_timing_profiles');
  return v_count;
exception when others then
  perform vec_finish_job('vec_build_timing_profiles');
  raise;
end;
$$;
