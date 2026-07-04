-- object: sync event retention indexes
-- folder: indexes
-- depends_on: sync_event_payload_archives, sync_event_tombstones
create index if not exists sync_event_payload_archives_observed_idx
  on sync_event_payload_archives (stream_name, observed_at desc);

create index if not exists sync_event_payload_archives_archived_idx
  on sync_event_payload_archives (archived_at desc);

create index if not exists sync_event_tombstones_expires_idx
  on sync_event_tombstones (expires_at);

create index if not exists sync_event_tombstones_stream_observed_idx
  on sync_event_tombstones (stream_name, observed_at desc);

