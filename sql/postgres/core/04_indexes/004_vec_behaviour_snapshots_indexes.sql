-- object: vec_behaviour_snapshots indexes
-- folder: indexes
-- depends_on: vec_behaviour_snapshots
create index if not exists vec_behaviour_snapshots_mac_time_idx
  on vec_behaviour_snapshots (source_mac, window_start desc);

create index if not exists vec_behaviour_snapshots_location_time_idx
  on vec_behaviour_snapshots (location_id, window_start desc);
