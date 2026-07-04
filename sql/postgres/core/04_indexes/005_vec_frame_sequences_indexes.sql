-- object: vec_frame_sequences indexes
-- folder: indexes
-- depends_on: vec_frame_sequences
create index if not exists vec_frame_sequences_sensor_idx
  on vec_frame_sequences (sensor_id, window_start desc);

create index if not exists vec_frame_sequences_location_idx
  on vec_frame_sequences (location_id, window_start desc);
