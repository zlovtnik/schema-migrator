-- object: vec_timing_profiles indexes
-- folder: indexes
-- depends_on: vec_timing_profiles
create index if not exists vec_timing_profiles_source_mac_idx
  on vec_timing_profiles (source_mac, window_start desc);

create index if not exists vec_timing_profiles_sensor_window_idx
  on vec_timing_profiles (sensor_id, location_id, window_start desc);
