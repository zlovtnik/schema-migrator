-- object: vec_frame_sequences
-- folder: tables
-- depends_on: wireless_frames
create table if not exists vec_frame_sequences (
  session_key text primary key,
  source_mac text,
  location_id text,
  sensor_id text,
  window_start timestamptz not null,
  window_end timestamptz not null,
  sequence_tokens text not null,
  semantic_tokens text,
  frame_count bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table vec_frame_sequences
  add column if not exists semantic_tokens text;
