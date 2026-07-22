-- object: retire unused routines
-- folder: functions
-- depends_on: coordinator

drop function if exists coordinator.record_scan_request(jsonb, jsonb, text, text[]);
drop function if exists coordinator.process_batch_result(jsonb);
drop function if exists vec_assign_device_cluster(text);
drop function if exists vec_upsert_similarity_pair(
  text,
  text,
  text,
  bigint,
  bigint,
  text,
  text,
  text,
  text,
  double precision,
  double precision,
  integer,
  jsonb
);
