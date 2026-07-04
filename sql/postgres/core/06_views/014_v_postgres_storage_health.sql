-- object: v_postgres_storage_health
-- folder: views
-- depends_on: sync_events, sync_event_payload_archives, sync_event_tombstones, vec_embeddings
create or replace view v_postgres_storage_health as
with relation_stats as (
  select
    relid,
    relname,
    pg_total_relation_size(relid)::bigint as total_bytes,
    pg_relation_size(relid)::bigint as table_bytes,
    (pg_total_relation_size(relid) - pg_relation_size(relid))::bigint as index_bytes,
    n_live_tup::bigint as live_tuples,
    n_dead_tup::bigint as dead_tuples,
    case
      when n_live_tup + n_dead_tup > 0
        then n_dead_tup::double precision / nullif(n_live_tup + n_dead_tup, 0)
      else 0::double precision
    end as dead_tuple_ratio,
    last_autovacuum,
    case
      when last_autovacuum is not null then extract(epoch from now() - last_autovacuum)::bigint
    end as autovacuum_age_seconds
  from pg_stat_user_tables
  where relname in (
    'sync_events',
    'wireless_frames',
    'sync_event_payload_archives',
    'sync_event_tombstones',
    'vec_embeddings',
    'vec_embedding_jobs',
    'vec_similarity_pairs',
    'vec_behaviour_snapshots',
    'vec_frame_sequences',
    'vec_timing_profiles'
  )
),
wireless_payloads as (
  select
    count(*) filter (where payload is not null)::bigint as hot_payload_count,
    coalesce(sum(pg_column_size(payload)) filter (where payload is not null), 0)::bigint as hot_payload_bytes,
    count(*) filter (
      where payload is not null
        and observed_at < now() - interval '7 days'
        and not exists (
          select 1
          from sync_event_payload_archives archive
          where archive.dedupe_key = sync_events.dedupe_key
        )
    )::bigint as unarchived_payload_count,
    min(observed_at) filter (
      where payload is not null
        and observed_at < now() - interval '7 days'
        and not exists (
          select 1
          from sync_event_payload_archives archive
          where archive.dedupe_key = sync_events.dedupe_key
        )
    ) as oldest_unarchived_payload_at
  from sync_events
  where stream_name = 'wireless.audit'
),
archive_stats as (
  select
    count(*)::bigint as archived_payload_count,
    coalesce(sum(payload_bytes), 0)::bigint as archived_payload_bytes,
    max(archived_at) as last_archived_at
  from sync_event_payload_archives
),
tombstone_stats as (
  select
    count(*)::bigint as tombstone_count,
    count(*) filter (where expires_at <= now())::bigint as expired_tombstone_count,
    min(expires_at) as next_tombstone_expiry_at
  from sync_event_tombstones
),
vector_stats as (
  select
    coalesce(jsonb_object_agg(embedding_kind, row_count order by embedding_kind), '{}'::jsonb) as vector_rows_by_kind
  from (
    select embedding_kind, count(*)::bigint as row_count
    from vec_embeddings
    group by embedding_kind
  ) grouped
)
select
  now() as measured_at,
  coalesce(
    (
      select jsonb_agg(
        jsonb_build_object(
          'relation', relname,
          'total_bytes', total_bytes,
          'table_bytes', table_bytes,
          'index_bytes', index_bytes,
          'live_tuples', live_tuples,
          'dead_tuples', dead_tuples,
          'dead_tuple_ratio', dead_tuple_ratio,
          'last_autovacuum', last_autovacuum,
          'autovacuum_age_seconds', autovacuum_age_seconds
        )
        order by total_bytes desc
      )
      from relation_stats
    ),
    '[]'::jsonb
  ) as relation_storage,
  wireless_payloads.hot_payload_count,
  wireless_payloads.hot_payload_bytes,
  wireless_payloads.unarchived_payload_count,
  wireless_payloads.oldest_unarchived_payload_at,
  case
    when wireless_payloads.oldest_unarchived_payload_at is not null
      then extract(epoch from now() - wireless_payloads.oldest_unarchived_payload_at)::bigint
    else 0::bigint
  end as archive_lag_seconds,
  archive_stats.archived_payload_count,
  archive_stats.archived_payload_bytes,
  archive_stats.last_archived_at,
  tombstone_stats.tombstone_count,
  tombstone_stats.expired_tombstone_count,
  tombstone_stats.next_tombstone_expiry_at,
  vector_stats.vector_rows_by_kind
from wireless_payloads
cross join archive_stats
cross join tombstone_stats
cross join vector_stats;
