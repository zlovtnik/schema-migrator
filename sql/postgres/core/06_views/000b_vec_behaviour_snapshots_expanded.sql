-- object: vec_behaviour_snapshots_expanded
-- folder: views
-- depends_on: vec_behaviour_snapshots, vec_behaviour_snapshot_stats
create or replace view vec_behaviour_snapshots_expanded as
select
  snapshot.snapshot_id,
  snapshot.snapshot_key,
  snapshot.source_mac,
  snapshot.location_id,
  snapshot.sensor_id,
  snapshot.window_start,
  snapshot.window_end,
  snapshot.event_count,
  stats.protocol_mix,
  stats.frame_type_distribution,
  stats.signal_min_dbm,
  stats.signal_max_dbm,
  stats.signal_avg_dbm,
  stats.retry_count,
  stats.protected_count,
  stats.unprotected_count,
  stats.unique_bssid_count,
  stats.mac_rotation_indicators,
  snapshot.text_summary,
  snapshot.embedding_text,
  snapshot.created_at,
  snapshot.updated_at
from vec_behaviour_snapshots snapshot
join vec_behaviour_snapshot_stats stats using (snapshot_id);
