-- object: coordinator event retention
-- folder: functions
-- depends_on: sync_events, wireless_frames, sync_event_payload_archives, sync_event_tombstones
create or replace function coordinator.list_wireless_payload_archive_candidates(
  p_hot_days integer default 7,
  p_limit integer default 100
)
returns table (
  dedupe_key text,
  stream_name text,
  observed_at timestamptz,
  payload_sha256 text,
  payload_bytes bigint,
  payload jsonb
)
language sql
volatile
as $$
  select
    event.dedupe_key,
    event.stream_name,
    event.observed_at,
    event.payload_sha256,
    pg_column_size(event.payload)::bigint as payload_bytes,
    event.payload
  from sync_events event
  left join sync_event_payload_archives archive
    on archive.dedupe_key = event.dedupe_key
  where event.stream_name = 'wireless.audit'
    and event.status = 'batched'
    and event.payload is not null
    and event.observed_at < now() - make_interval(days => greatest(coalesce(p_hot_days, 7), 1))
    and archive.dedupe_key is null
  order by event.observed_at asc, event.dedupe_key asc
  limit greatest(coalesce(p_limit, 100), 1)
  for update of event skip locked
$$;

create or replace function coordinator.record_payload_archive(
  p_dedupe_key text,
  p_payload_sha256 text,
  p_archive_uri text,
  p_payload_bytes bigint
)
returns boolean
language plpgsql
as $$
declare
  v_event record;
  v_updated integer := 0;
begin
  if nullif(p_dedupe_key, '') is null then
    raise exception 'payload archive missing dedupe_key';
  end if;
  if nullif(p_archive_uri, '') is null then
    raise exception 'payload archive missing archive_uri';
  end if;

  select dedupe_key, stream_name, observed_at, payload_sha256
    into v_event
    from sync_events
   where dedupe_key = p_dedupe_key
     and payload is not null
   for update;

  if not found then
    return false;
  end if;

  if p_payload_sha256 is not null
     and v_event.payload_sha256 is not null
     and v_event.payload_sha256 <> p_payload_sha256 then
    return false;
  end if;

  insert into sync_event_payload_archives (
    dedupe_key,
    stream_name,
    observed_at,
    payload_sha256,
    archive_uri,
    payload_bytes,
    archived_at,
    created_at,
    updated_at
  )
  values (
    v_event.dedupe_key,
    v_event.stream_name,
    v_event.observed_at,
    coalesce(p_payload_sha256, v_event.payload_sha256),
    p_archive_uri,
    greatest(coalesce(p_payload_bytes, 0), 0),
    now(),
    now(),
    now()
  )
  on conflict (dedupe_key) do update set
    payload_sha256 = excluded.payload_sha256,
    archive_uri = excluded.archive_uri,
    payload_bytes = excluded.payload_bytes,
    archived_at = now(),
    updated_at = now();

  update sync_events
     set payload = null,
         updated_at = now()
   where dedupe_key = p_dedupe_key
     and payload is not null
     and (
       p_payload_sha256 is null
       or payload_sha256 is null
       or payload_sha256 = p_payload_sha256
     );
  get diagnostics v_updated = row_count;

  return v_updated > 0;
end;
$$;

create or replace function coordinator.prune_sync_event_retention(
  p_event_retention_days integer default 30,
  p_tombstone_retention_days integer default 45,
  p_limit integer default 5000
)
returns jsonb
language plpgsql
as $$
declare
  v_tombstoned integer := 0;
  v_wireless_frames_deleted integer := 0;
  v_deleted integer := 0;
  v_expired_tombstones integer := 0;
begin
  with candidates as materialized (
    select
      event.dedupe_key,
      event.stream_name,
      event.payload_sha256,
      event.observed_at
    from sync_events event
    where event.observed_at < now() - make_interval(days => greatest(coalesce(p_event_retention_days, 30), 1))
      and event.status not in ('pending', 'processing')
      and (
        event.stream_name <> 'wireless.audit'
        or event.payload is null
        or exists (
          select 1
          from sync_event_payload_archives archive
          where archive.dedupe_key = event.dedupe_key
        )
      )
    order by event.observed_at asc, event.dedupe_key asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  tombstoned as (
    insert into sync_event_tombstones (
      dedupe_key,
      stream_name,
      payload_sha256,
      observed_at,
      expires_at,
      created_at,
      updated_at
    )
    select
      dedupe_key,
      stream_name,
      payload_sha256,
      observed_at,
      now() + make_interval(days => greatest(coalesce(p_tombstone_retention_days, 45), 1)),
      now(),
      now()
    from candidates
    on conflict (dedupe_key) do update set
      stream_name = excluded.stream_name,
      payload_sha256 = excluded.payload_sha256,
      observed_at = excluded.observed_at,
      expires_at = greatest(sync_event_tombstones.expires_at, excluded.expires_at),
      updated_at = now()
    returning 1
  )
  select count(*) into v_tombstoned from tombstoned;

  with candidates as materialized (
    select event.dedupe_key
    from sync_events event
    where event.observed_at < now() - make_interval(days => greatest(coalesce(p_event_retention_days, 30), 1))
      and event.status not in ('pending', 'processing')
      and exists (
        select 1
        from sync_event_tombstones tombstone
        where tombstone.dedupe_key = event.dedupe_key
      )
      and (
        event.stream_name <> 'wireless.audit'
        or event.payload is null
        or exists (
          select 1
          from sync_event_payload_archives archive
          where archive.dedupe_key = event.dedupe_key
        )
      )
    order by event.observed_at asc, event.dedupe_key asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  wireless_deleted as (
    delete from wireless_frames frame
    using candidates
    where frame.dedupe_key = candidates.dedupe_key
    returning 1
  )
  select count(*) into v_wireless_frames_deleted from wireless_deleted;

  with candidates as materialized (
    select event.dedupe_key
    from sync_events event
    where event.observed_at < now() - make_interval(days => greatest(coalesce(p_event_retention_days, 30), 1))
      and event.status not in ('pending', 'processing')
      and exists (
        select 1
        from sync_event_tombstones tombstone
        where tombstone.dedupe_key = event.dedupe_key
      )
      and (
        event.stream_name <> 'wireless.audit'
        or event.payload is null
        or exists (
          select 1
          from sync_event_payload_archives archive
          where archive.dedupe_key = event.dedupe_key
        )
      )
    order by event.observed_at asc, event.dedupe_key asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  deleted as (
    delete from sync_events event
    using candidates
    where event.dedupe_key = candidates.dedupe_key
    returning 1
  )
  select count(*) into v_deleted from deleted;

  delete from sync_event_tombstones
   where expires_at < now();
  get diagnostics v_expired_tombstones = row_count;

  return jsonb_build_object(
    'tombstoned', v_tombstoned,
    'wireless_frames_deleted', v_wireless_frames_deleted,
    'deleted', v_deleted,
    'expired_tombstones', v_expired_tombstones
  );
end;
$$;
