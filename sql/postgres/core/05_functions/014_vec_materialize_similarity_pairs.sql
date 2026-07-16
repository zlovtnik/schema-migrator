-- object: vec_materialize_similarity_pairs
-- folder: functions
-- depends_on: vec_similarity_pairs, sync_cursors, vec_job_locks
drop function if exists vec_materialize_similarity_pairs(text, integer, double precision, double precision);
drop function if exists vec_materialize_similarity_pairs(text, integer, double precision, double precision, double precision);
drop function if exists vec_materialize_similarity_pairs(text, integer, double precision, double precision, double precision, double precision);

create or replace function vec_materialize_similarity_pairs(
  p_model text default 'nomic-embed-text-v2-moe',
  p_top_k integer default 10,
  p_event_dup_distance_threshold double precision default 0.05,
  p_behaviour_similarity_threshold double precision default 0.88,
  p_sequence_similarity_threshold double precision default 0.10,
  p_timing_similarity_threshold double precision default 0.05
)
returns integer
language plpgsql
as $$
declare
  v_total integer := 0;
  v_count integer := 0;
  v_started_at timestamptz := now();
begin
  if not vec_try_begin_job('vec_materialize_similarity_pairs') then
    return 0;
  end if;

  insert into sync_cursors (stream_name, cursor_value, updated_at)
  values ('vec_similarity_pairs.last_run', '1970-01-01T00:00:00+00:00', now())
  on conflict (stream_name) do nothing;

  with last_run as materialized (
    select cursor_value::timestamptz as ts
    from sync_cursors
    where stream_name = 'vec_similarity_pairs.last_run'
  ),
  candidates as (
    select
      least(e1.embedding_id, neighbor.embedding_id) as left_embedding_id,
      greatest(e1.embedding_id, neighbor.embedding_id) as right_embedding_id,
      min(neighbor.cosine_distance) as cosine_distance
    from vec_embeddings_expanded e1
    cross join last_run
    join lateral (
      select
        e2.embedding_id,
        (e2.embedding::vector(768) <=> e1.embedding::vector(768)) as cosine_distance
      from vec_embeddings_expanded e2
      where e2.embedding_kind = 'event'
        and e2.embedding_model = p_model
        and e2.embedding_dimensions = 768
        and e2.embedding_id <> e1.embedding_id
      order by e2.embedding::vector(768) <=> e1.embedding::vector(768)
      limit greatest(p_top_k, 1)
    ) neighbor on true
    where e1.embedding_kind = 'event'
      and e1.embedding_model = p_model
      and e1.embedding_dimensions = 768
      and e1.embedded_at > last_run.ts
      and e1.embedded_at <= v_started_at
      and neighbor.cosine_distance <= p_event_dup_distance_threshold
    group by least(e1.embedding_id, neighbor.embedding_id), greatest(e1.embedding_id, neighbor.embedding_id)
  )
  select count(*) into v_count
  from (
    select vec_upsert_similarity_pair(
      'event_event', p_model, 'event',
      candidates.left_embedding_id, candidates.right_embedding_id,
      left_e.source_table, left_e.source_key,
      right_e.source_table, right_e.source_key,
      candidates.cosine_distance, 1 - candidates.cosine_distance, 1,
      jsonb_build_object('threshold', p_event_dup_distance_threshold, 'detector', 'near_duplicate_event')
    ) as pair_id
    from candidates
    join vec_embeddings_expanded left_e on left_e.embedding_id = candidates.left_embedding_id
    join vec_embeddings_expanded right_e on right_e.embedding_id = candidates.right_embedding_id
  ) upserted;
  v_total := v_total + v_count;

  with last_run as materialized (
    select cursor_value::timestamptz as ts
    from sync_cursors
    where stream_name = 'vec_similarity_pairs.last_run'
  ),
  candidates as (
    select
      least(e1.embedding_id, neighbor.embedding_id) as left_embedding_id,
      greatest(e1.embedding_id, neighbor.embedding_id) as right_embedding_id,
      min(neighbor.cosine_distance) as cosine_distance
    from vec_embeddings_expanded e1
    cross join last_run
    join lateral (
      select
        e2.embedding_id,
        (e2.embedding::vector(768) <=> e1.embedding::vector(768)) as cosine_distance
      from vec_embeddings_expanded e2
      where e2.embedding_kind = 'event'
        and e2.embedding_model = p_model
        and e2.embedding_dimensions = 768
        and e2.embedding_id <> e1.embedding_id
        and (
          e2.source_sensor_id is distinct from e1.source_sensor_id
          or e2.source_stream_name is distinct from e1.source_stream_name
        )
      order by e2.embedding::vector(768) <=> e1.embedding::vector(768)
      limit greatest(p_top_k, 1)
    ) neighbor on true
    where e1.embedding_kind = 'event'
      and e1.embedding_model = p_model
      and e1.embedding_dimensions = 768
      and e1.embedded_at > last_run.ts
      and e1.embedded_at <= v_started_at
      and neighbor.cosine_distance <= greatest(p_event_dup_distance_threshold * 3, p_event_dup_distance_threshold)
    group by least(e1.embedding_id, neighbor.embedding_id), greatest(e1.embedding_id, neighbor.embedding_id)
  )
  select count(*) into v_count
  from (
    select vec_upsert_similarity_pair(
      'cross_sensor', p_model, 'event',
      candidates.left_embedding_id, candidates.right_embedding_id,
      left_e.source_table, left_e.source_key,
      right_e.source_table, right_e.source_key,
      candidates.cosine_distance, 1 - candidates.cosine_distance, 1,
      jsonb_build_object('detector', 'cross_sensor_event_cluster')
    ) as pair_id
    from candidates
    join vec_embeddings_expanded left_e on left_e.embedding_id = candidates.left_embedding_id
    join vec_embeddings_expanded right_e on right_e.embedding_id = candidates.right_embedding_id
  ) upserted;
  v_total := v_total + v_count;

  with last_run as materialized (
    select cursor_value::timestamptz as ts
    from sync_cursors
    where stream_name = 'vec_similarity_pairs.last_run'
  ),
  candidates as (
    select
      least(e1.embedding_id, neighbor.embedding_id) as left_embedding_id,
      greatest(e1.embedding_id, neighbor.embedding_id) as right_embedding_id,
      min(neighbor.cosine_distance) as cosine_distance
    from vec_embeddings_expanded e1
    cross join last_run
    join vec_behaviour_snapshots_expanded s1 on s1.snapshot_id::text = e1.source_key
    join lateral (
      select
        e2.embedding_id,
        (e2.embedding::vector(768) <=> e1.embedding::vector(768)) as cosine_distance
      from vec_embeddings_expanded e2
      join vec_behaviour_snapshots_expanded s2 on s2.snapshot_id::text = e2.source_key
      where e2.embedding_kind = 'behaviour_window'
        and e2.embedding_model = p_model
        and e2.embedding_dimensions = 768
        and e2.embedding_id <> e1.embedding_id
        and s2.source_mac <> s1.source_mac
        and s2.location_id is not distinct from s1.location_id
      order by e2.embedding::vector(768) <=> e1.embedding::vector(768)
      limit greatest(p_top_k, 1)
    ) neighbor on true
    where e1.embedding_kind = 'behaviour_window'
      and e1.embedding_model = p_model
      and e1.embedding_dimensions = 768
      and e1.embedded_at > last_run.ts
      and e1.embedded_at <= v_started_at
      and neighbor.cosine_distance <= (1 - p_behaviour_similarity_threshold)
    group by least(e1.embedding_id, neighbor.embedding_id), greatest(e1.embedding_id, neighbor.embedding_id)
  )
  select count(*) into v_count
  from (
    select vec_upsert_similarity_pair(
      'device_device', p_model, 'behaviour_window',
      candidates.left_embedding_id, candidates.right_embedding_id,
      left_e.source_table, left_e.source_key,
      right_e.source_table, right_e.source_key,
      candidates.cosine_distance, 1 - candidates.cosine_distance, 1,
      jsonb_build_object('threshold', p_behaviour_similarity_threshold, 'detector', 'mac_rotation_suspected')
    ) as pair_id
    from candidates
    join vec_embeddings_expanded left_e on left_e.embedding_id = candidates.left_embedding_id
    join vec_embeddings_expanded right_e on right_e.embedding_id = candidates.right_embedding_id
  ) upserted;
  v_total := v_total + v_count;

  with last_run as materialized (
    select cursor_value::timestamptz as ts
    from sync_cursors
    where stream_name = 'vec_similarity_pairs.last_run'
  ),
  candidates as (
    select
      least(e1.embedding_id, neighbor.embedding_id) as left_embedding_id,
      greatest(e1.embedding_id, neighbor.embedding_id) as right_embedding_id,
      min(neighbor.cosine_distance) as cosine_distance
    from vec_embeddings_expanded e1
    cross join last_run
    join lateral (
      select
        e2.embedding_id,
        (e2.embedding::vector(768) <=> e1.embedding::vector(768)) as cosine_distance
      from vec_embeddings_expanded e2
      where e2.embedding_kind = 'frame_sequence'
        and e2.embedding_model = p_model
        and e2.embedding_dimensions = 768
        and e2.embedding_id <> e1.embedding_id
      order by e2.embedding::vector(768) <=> e1.embedding::vector(768)
      limit greatest(p_top_k, 1)
    ) neighbor on true
    where e1.embedding_kind = 'frame_sequence'
      and e1.embedding_model = p_model
      and e1.embedding_dimensions = 768
      and e1.embedded_at > last_run.ts
      and e1.embedded_at <= v_started_at
      and neighbor.cosine_distance <= p_sequence_similarity_threshold
    group by least(e1.embedding_id, neighbor.embedding_id), greatest(e1.embedding_id, neighbor.embedding_id)
  )
  select count(*) into v_count
  from (
    select vec_upsert_similarity_pair(
      'sequence_sequence', p_model, 'frame_sequence',
      candidates.left_embedding_id, candidates.right_embedding_id,
      left_e.source_table, left_e.source_key,
      right_e.source_table, right_e.source_key,
      candidates.cosine_distance, 1 - candidates.cosine_distance, 1,
      jsonb_build_object('detector', 'similar_frame_sequence', 'threshold', p_sequence_similarity_threshold)
    ) as pair_id
    from candidates
    join vec_embeddings_expanded left_e on left_e.embedding_id = candidates.left_embedding_id
    join vec_embeddings_expanded right_e on right_e.embedding_id = candidates.right_embedding_id
  ) upserted;
  v_total := v_total + v_count;

  with last_run as materialized (
    select cursor_value::timestamptz as ts
    from sync_cursors
    where stream_name = 'vec_similarity_pairs.last_run'
  ),
  candidates as (
    select
      least(e1.embedding_id, neighbor.embedding_id) as left_embedding_id,
      greatest(e1.embedding_id, neighbor.embedding_id) as right_embedding_id,
      min(neighbor.cosine_distance) as cosine_distance
    from vec_embeddings_expanded e1
    cross join last_run
    join lateral (
      select
        e2.embedding_id,
        (e2.embedding::vector(768) <=> e1.embedding::vector(768)) as cosine_distance
      from vec_embeddings_expanded e2
      where e2.embedding_kind = 'timing_profile'
        and e2.embedding_model = p_model
        and e2.embedding_dimensions = 768
        and e2.embedding_id <> e1.embedding_id
        and e2.source_mac is not null
        and e1.source_mac is not null
        and e2.source_mac <> e1.source_mac
        and e2.source_sensor_id is not distinct from e1.source_sensor_id
        and e2.source_location_id is not distinct from e1.source_location_id
      order by e2.embedding::vector(768) <=> e1.embedding::vector(768)
      limit greatest(p_top_k, 1)
    ) neighbor on true
    where e1.embedding_kind = 'timing_profile'
      and e1.embedding_model = p_model
      and e1.embedding_dimensions = 768
      and e1.embedded_at > last_run.ts
      and e1.embedded_at <= v_started_at
      and neighbor.cosine_distance <= p_timing_similarity_threshold
    group by least(e1.embedding_id, neighbor.embedding_id), greatest(e1.embedding_id, neighbor.embedding_id)
  )
  select count(*) into v_count
  from (
    select vec_upsert_similarity_pair(
      'timing_timing', p_model, 'timing_profile',
      candidates.left_embedding_id, candidates.right_embedding_id,
      left_e.source_table, left_e.source_key,
      right_e.source_table, right_e.source_key,
      candidates.cosine_distance, 1 - candidates.cosine_distance, 1,
      jsonb_build_object('detector', 'timing_fingerprint_match', 'threshold', p_timing_similarity_threshold)
    ) as pair_id
    from candidates
    join vec_embeddings_expanded left_e on left_e.embedding_id = candidates.left_embedding_id
    join vec_embeddings_expanded right_e on right_e.embedding_id = candidates.right_embedding_id
  ) upserted;
  v_total := v_total + v_count;

  insert into sync_cursors (stream_name, cursor_value, updated_at)
  values ('vec_similarity_pairs.last_run', v_started_at::text, now())
  on conflict (stream_name) do update
    set cursor_value = excluded.cursor_value,
        updated_at = now();

  perform vec_finish_job('vec_materialize_similarity_pairs');
  return v_total;
exception when others then
  perform vec_finish_job('vec_materialize_similarity_pairs');
  raise;
end;
$$;
