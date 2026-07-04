-- object: v_vec_similarity_audit
-- folder: views
-- depends_on: vec_similarity_pairs, vec_embeddings
create or replace view v_vec_similarity_audit as
select
  pair.pair_id,
  pair.pair_kind,
  pair.embedding_model,
  pair.embedding_kind,
  pair.cosine_distance,
  pair.cosine_similarity,
  pair.rank,
  pair.evidence,
  pair.computed_at,
  pair.left_source_table,
  pair.left_source_key,
  pair.left_source_mac,
  pair.left_sensor_id,
  pair.left_location_id,
  pair.left_observed_at,
  left_event.stream_name as left_stream_name,
  left_event.ssid as left_ssid,
  left_event.bssid as left_bssid,
  left_event.destination_bssid as left_destination_bssid,
  left_device.display_name as left_device_display_name,
  left_snapshot.snapshot_id as left_snapshot_id,
  left_snapshot.window_start as left_window_start,
  left_snapshot.window_end as left_window_end,
  pair.right_source_table,
  pair.right_source_key,
  pair.right_source_mac,
  pair.right_sensor_id,
  pair.right_location_id,
  pair.right_observed_at,
  right_event.stream_name as right_stream_name,
  right_event.ssid as right_ssid,
  right_event.bssid as right_bssid,
  right_event.destination_bssid as right_destination_bssid,
  right_device.display_name as right_device_display_name,
  right_snapshot.snapshot_id as right_snapshot_id,
  right_snapshot.window_start as right_window_start,
  right_snapshot.window_end as right_window_end
from vec_similarity_pairs pair
left join sync_events_expanded left_event
  on pair.left_source_table = 'sync_events'
 and left_event.dedupe_key = pair.left_source_key
left join sync_events_expanded right_event
  on pair.right_source_table = 'sync_events'
 and right_event.dedupe_key = pair.right_source_key
left join devices left_device
  on left_device.mac_id = pair.left_source_key
  or left_device.mac_id = lower(pair.left_source_mac)
left join devices right_device
  on right_device.mac_id = pair.right_source_key
  or right_device.mac_id = lower(pair.right_source_mac)
left join vec_behaviour_snapshots left_snapshot
  on pair.left_source_table = 'vec_behaviour_snapshots'
 and left_snapshot.snapshot_id::text = pair.left_source_key
left join vec_behaviour_snapshots right_snapshot
  on pair.right_source_table = 'vec_behaviour_snapshots'
 and right_snapshot.snapshot_id::text = pair.right_source_key;
