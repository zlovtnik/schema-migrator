-- object: sync_events_wireless_batched_updated_idx (replacement)
-- folder: indexes
-- depends_on: sync_events
-- Extracted from 001_sync_events_indexes.sql to avoid modifying the historical
-- migration. Created by 044_vec_complete_embedding_batch_replace.sql.
create index if not exists sync_events_wireless_batched_updated_idx
  on sync_events (updated_at, dedupe_key)
  where stream_name = 'wireless.audit'
    and status = 'batched';