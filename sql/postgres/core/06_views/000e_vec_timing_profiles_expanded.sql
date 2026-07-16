-- object: vec_timing_profiles_expanded
-- folder: views
-- depends_on: vec_timing_profiles, vec_timing_profile_stats
create or replace view vec_timing_profiles_expanded as
select
  profile.profile_id,
  profile.profile_key,
  profile.source_mac,
  profile.sensor_id,
  profile.location_id,
  profile.window_start,
  profile.window_end,
  stats.tsft_p50_us,
  stats.tsft_p95_us,
  stats.tsft_jitter,
  stats.wall_p50_ms,
  stats.wall_jitter_ms,
  stats.beacon_interval_median_ms,
  stats.beacon_jitter_ms,
  profile.embedding_text,
  profile.created_at,
  profile.updated_at
from vec_timing_profiles profile
join vec_timing_profile_stats stats using (profile_id);
