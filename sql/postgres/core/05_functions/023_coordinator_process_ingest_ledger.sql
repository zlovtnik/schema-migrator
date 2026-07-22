-- object: coordinator.process_ingest_ledger
-- folder: functions
-- depends_on: sync_events, sync_jobs, sync_batches
drop function if exists coordinator.process_ingest_ledger(text[], integer, integer);

create or replace function coordinator.process_ingest_ledger(
  p_stream_names text[],
  p_oracle_stream_names text[],
  p_max_attempts integer,
  p_backoff_secs integer,
  p_batch_size integer default 200
)
returns integer
language plpgsql
as $$
declare
  v_marked_count integer := 0;
  v_recovered_count integer := 0;
  v_batched_count integer := 0;
  v_limit integer := greatest(coalesce(p_batch_size, 200), 1);
  v_processed_dedupe_keys text[] := array[]::text[];
  v_processed_stream_names text[] := array[]::text[];
begin
  update sync_events ingest
     set status = 'batched',
         updated_at = now()
   where status = 'processing'
     and exists (
       select 1
        from sync_batches batch
        where batch.dedupe_key = ingest.dedupe_key
          and batch.stream_name = ingest.stream_name
     );
  get diagnostics v_marked_count = row_count;

  update sync_events ingest
     set status = 'failed',
         updated_at = now(),
         last_error = coalesce(ingest.last_error, 'coordinator processing lease expired')
   where status = 'processing'
     and updated_at < now() - interval '5 minutes'
     and not exists (
       select 1
        from sync_batches batch
        where batch.dedupe_key = ingest.dedupe_key
          and batch.stream_name = ingest.stream_name
     );
  get diagnostics v_recovered_count = row_count;

  with next_ingest as (
    update sync_events
       set status = 'processing',
           attempt_count = attempt_count + 1,
           updated_at = now(),
           last_error = null
     where (dedupe_key, stream_name) in (
       select dedupe_key, stream_name
         from sync_events
        where status in ('pending', 'failed')
          and stream_name in (
                select btrim(configured.stream_name)
                  from unnest(p_stream_names) as configured(stream_name)
              )
          and attempt_count < p_max_attempts
          and (
                status = 'pending'
                or observed_at <= now() - make_interval(secs => (greatest(attempt_count, 1) * p_backoff_secs))
              )
        order by observed_at asc
        limit v_limit
        for update skip locked
     )
    returning dedupe_key, stream_name
  )
  select coalesce(array_agg(dedupe_key order by dedupe_key, stream_name), array[]::text[]),
         coalesce(array_agg(stream_name order by dedupe_key, stream_name), array[]::text[])
    into v_processed_dedupe_keys, v_processed_stream_names
    from next_ingest;

  insert into sync_cursors (stream_name, cursor_value, updated_at)
  select distinct stream_name, '0', now()
    from sync_events
   where (dedupe_key, stream_name) in (
     select processed.dedupe_key, processed.stream_name
     from unnest(v_processed_dedupe_keys, v_processed_stream_names)
       as processed(dedupe_key, stream_name)
   )
  on conflict (stream_name) do nothing;

  insert into sync_jobs (job_id, stream_name, status, attempt_count, created_at, started_at)
  select sync_stable_uuid(dedupe_key || ':' || stream_name || ':job'),
         stream_name,
         'pending',
         0,
         now(),
         now()
    from sync_events
   where (dedupe_key, stream_name) in (
     select processed.dedupe_key, processed.stream_name
     from unnest(v_processed_dedupe_keys, v_processed_stream_names)
       as processed(dedupe_key, stream_name)
   )
     and stream_name in (
           select btrim(configured.stream_name)
             from unnest(p_oracle_stream_names) as configured(stream_name)
     )
  on conflict (job_id) do nothing;

  insert into sync_batches (
    batch_id,
    job_id,
    batch_no,
    payload_ref,
    status,
    row_count,
    checksum,
    attempt_count,
    last_error,
    dedupe_key,
    stream_name,
    cursor_start,
    cursor_end
  )
  select sync_stable_uuid(ingest.dedupe_key || ':' || ingest.stream_name || ':batch'),
         sync_stable_uuid(ingest.dedupe_key || ':' || ingest.stream_name || ':job'),
         0,
         ingest.payload_ref,
         'pending',
         1,
         ingest.payload_sha256,
         0,
         null,
         ingest.dedupe_key,
         ingest.stream_name,
         cursor.cursor_value,
         extract(epoch from ingest.observed_at)::bigint::text
    from sync_events ingest
    join sync_cursors cursor on cursor.stream_name = ingest.stream_name
   where (ingest.dedupe_key, ingest.stream_name) in (
     select processed.dedupe_key, processed.stream_name
     from unnest(v_processed_dedupe_keys, v_processed_stream_names)
       as processed(dedupe_key, stream_name)
   )
     and ingest.stream_name in (
           select btrim(configured.stream_name)
             from unnest(p_oracle_stream_names) as configured(stream_name)
     )
  on conflict (dedupe_key, stream_name) do nothing;

  update sync_events ingest
     set status = 'batched',
         updated_at = now()
   where (ingest.dedupe_key, ingest.stream_name) in (
     select processed.dedupe_key, processed.stream_name
     from unnest(v_processed_dedupe_keys, v_processed_stream_names)
       as processed(dedupe_key, stream_name)
   )
     and (
           ingest.stream_name not in (
             select btrim(configured.stream_name)
               from unnest(p_oracle_stream_names) as configured(stream_name)
           )
           or exists (
             select 1
              from sync_batches batch
              where batch.dedupe_key = ingest.dedupe_key
                and batch.stream_name = ingest.stream_name
           )
     );
  get diagnostics v_batched_count = row_count;

  insert into sync_cursors (stream_name, cursor_value, updated_at)
  select distinct on (stream_name)
         stream_name,
         extract(epoch from observed_at)::bigint::text,
         now()
    from sync_events
   where (dedupe_key, stream_name) in (
     select processed.dedupe_key, processed.stream_name
     from unnest(v_processed_dedupe_keys, v_processed_stream_names)
       as processed(dedupe_key, stream_name)
   )
   order by stream_name, observed_at desc
  on conflict (stream_name)
  do update set cursor_value = excluded.cursor_value, updated_at = now()
  where sync_cursors.cursor_value::bigint < excluded.cursor_value::bigint;

  return v_marked_count + v_recovered_count + v_batched_count;
end;
$$;
