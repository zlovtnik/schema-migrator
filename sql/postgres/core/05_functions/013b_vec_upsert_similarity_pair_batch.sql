-- object: vec_upsert_similarity_pair_batch
-- folder: functions
-- depends_on: vec_similarity_pairs, vec_similarity_pair_meta, vec_embedding_sources
create or replace function vec_upsert_similarity_pair_batch(p_candidates jsonb)
returns integer
language plpgsql
as $$
declare
  v_count integer := 0;
  v_existing_count integer := 0;
  v_inserted_count integer := 0;
begin
  if p_candidates is null
     or jsonb_typeof(p_candidates) <> 'array'
     or jsonb_array_length(p_candidates) = 0 then
    return 0;
  end if;

  if exists (
    select 1
    from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'vec_similarity_pairs'
      and column_name = 'pair_kind'
  ) then
    execute $legacy_batch$
      with input as materialized (
        select *
        from jsonb_to_recordset($1) as candidate(
          pair_kind text,
          embedding_model text,
          embedding_kind text,
          left_embedding_id bigint,
          right_embedding_id bigint,
          left_source_table text,
          left_source_key text,
          right_source_table text,
          right_source_key text,
          cosine_distance double precision,
          cosine_similarity double precision,
          rank integer,
          evidence jsonb
        )
      ),
      upserted as (
        insert into vec_similarity_pairs (
          pair_kind, embedding_model, embedding_kind,
          left_embedding_id, right_embedding_id,
          left_source_table, left_source_key, left_source_mac,
          left_sensor_id, left_location_id, left_observed_at,
          right_source_table, right_source_key, right_source_mac,
          right_sensor_id, right_location_id, right_observed_at,
          cosine_distance, cosine_similarity, rank, evidence,
          computed_at, created_at, updated_at
        )
        select
          input.pair_kind, input.embedding_model, input.embedding_kind,
          input.left_embedding_id, input.right_embedding_id,
          input.left_source_table, input.left_source_key, left_source.source_mac,
          left_source.source_sensor_id, left_source.source_location_id, left_source.source_observed_at,
          input.right_source_table, input.right_source_key, right_source.source_mac,
          right_source.source_sensor_id, right_source.source_location_id, right_source.source_observed_at,
          input.cosine_distance, input.cosine_similarity, input.rank,
          coalesce(input.evidence, '{}'::jsonb),
          now(), now(), now()
        from input
        left join vec_embedding_sources left_source
          on left_source.embedding_id = input.left_embedding_id
        left join vec_embedding_sources right_source
          on right_source.embedding_id = input.right_embedding_id
        on conflict (
          pair_kind, embedding_model, embedding_kind,
          left_embedding_id, right_embedding_id
        ) do update set
          left_source_table = excluded.left_source_table,
          left_source_key = excluded.left_source_key,
          left_source_mac = excluded.left_source_mac,
          left_sensor_id = excluded.left_sensor_id,
          left_location_id = excluded.left_location_id,
          left_observed_at = excluded.left_observed_at,
          right_source_table = excluded.right_source_table,
          right_source_key = excluded.right_source_key,
          right_source_mac = excluded.right_source_mac,
          right_sensor_id = excluded.right_sensor_id,
          right_location_id = excluded.right_location_id,
          right_observed_at = excluded.right_observed_at,
          cosine_distance = excluded.cosine_distance,
          cosine_similarity = excluded.cosine_similarity,
          rank = excluded.rank,
          evidence = excluded.evidence,
          computed_at = now(),
          updated_at = now()
        returning
          pair_id, pair_kind, embedding_model, embedding_kind,
          left_source_table, left_source_key, right_source_table, right_source_key,
          evidence, left_embedding_id, right_embedding_id
      )
      select count(*)::integer from upserted
    $legacy_batch$ into v_count using p_candidates;
  else
    with input as materialized (
      select *
      from jsonb_to_recordset(p_candidates) as candidate(
        pair_kind text,
        embedding_model text,
        embedding_kind text,
        left_embedding_id bigint,
        right_embedding_id bigint,
        left_source_table text,
        left_source_key text,
        right_source_table text,
        right_source_key text,
        cosine_distance double precision,
        cosine_similarity double precision,
        rank integer,
        evidence jsonb
      )
    ),
    existing as materialized (
      select input.*, meta.pair_id
      from input
      join vec_similarity_pair_meta meta
        on meta.pair_kind = input.pair_kind
       and meta.embedding_model = input.embedding_model
       and meta.embedding_kind = input.embedding_kind
       and meta.left_embedding_id = input.left_embedding_id
       and meta.right_embedding_id = input.right_embedding_id
    ),
    pairs_updated as (
      update vec_similarity_pairs pair
         set cosine_distance = existing.cosine_distance,
             cosine_similarity = existing.cosine_similarity,
             rank = existing.rank,
             computed_at = now(),
             updated_at = now()
        from existing
       where pair.pair_id = existing.pair_id
      returning pair.pair_id
    )
    select count(*)::integer into v_existing_count from pairs_updated;

    with input as materialized (
      select *
      from jsonb_to_recordset(p_candidates) as candidate(
        pair_kind text,
        embedding_model text,
        embedding_kind text,
        left_embedding_id bigint,
        right_embedding_id bigint,
        left_source_table text,
        left_source_key text,
        right_source_table text,
        right_source_key text,
        cosine_distance double precision,
        cosine_similarity double precision,
        rank integer,
        evidence jsonb
      )
    ),
    existing as materialized (
      select input.*, meta.pair_id
      from input
      join vec_similarity_pair_meta meta
        on meta.pair_kind = input.pair_kind
       and meta.embedding_model = input.embedding_model
       and meta.embedding_kind = input.embedding_kind
       and meta.left_embedding_id = input.left_embedding_id
       and meta.right_embedding_id = input.right_embedding_id
    )
    update vec_similarity_pair_meta meta
       set left_source_table = existing.left_source_table,
           left_source_key = existing.left_source_key,
           right_source_table = existing.right_source_table,
           right_source_key = existing.right_source_key,
           evidence = coalesce(existing.evidence, '{}'::jsonb)
      from existing
     where meta.pair_id = existing.pair_id;

    with input as materialized (
      select *
      from jsonb_to_recordset(p_candidates) as candidate(
        pair_kind text,
        embedding_model text,
        embedding_kind text,
        left_embedding_id bigint,
        right_embedding_id bigint,
        left_source_table text,
        left_source_key text,
        right_source_table text,
        right_source_key text,
        cosine_distance double precision,
        cosine_similarity double precision,
        rank integer,
        evidence jsonb
      )
    ),
    missing as materialized (
      select input.*
      from input
      where not exists (
        select 1
        from vec_similarity_pair_meta meta
        where meta.pair_kind = input.pair_kind
          and meta.embedding_model = input.embedding_model
          and meta.embedding_kind = input.embedding_kind
          and meta.left_embedding_id = input.left_embedding_id
          and meta.right_embedding_id = input.right_embedding_id
      )
    ),
    pairs_inserted as (
      insert into vec_similarity_pairs (
        left_embedding_id, right_embedding_id, cosine_distance,
        cosine_similarity, rank, computed_at, created_at, updated_at
      )
      select
        left_embedding_id, right_embedding_id, cosine_distance,
        cosine_similarity, rank, now(), now(), now()
      from missing
      returning pair_id, left_embedding_id, right_embedding_id
    ),
    meta_inserted as (
      insert into vec_similarity_pair_meta (
        pair_id, pair_kind, embedding_model, embedding_kind,
        left_source_table, left_source_key, right_source_table, right_source_key,
        evidence, left_embedding_id, right_embedding_id
      )
      select
        inserted.pair_id, missing.pair_kind, missing.embedding_model, missing.embedding_kind,
        missing.left_source_table, missing.left_source_key,
        missing.right_source_table, missing.right_source_key,
        coalesce(missing.evidence, '{}'::jsonb),
        missing.left_embedding_id, missing.right_embedding_id
      from pairs_inserted inserted
      join missing using (left_embedding_id, right_embedding_id)
      returning pair_id
    )
    select count(*)::integer into v_inserted_count from meta_inserted;

    v_count := v_existing_count + v_inserted_count;
  end if;

  return coalesce(v_count, 0);
end;
$$;
