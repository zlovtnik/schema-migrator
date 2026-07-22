-- object: vec_upsert_similarity_pair
-- folder: functions
-- depends_on: vec_similarity_pairs, vec_similarity_pair_meta
create or replace function vec_upsert_similarity_pair(
  p_pair_kind text,
  p_embedding_model text,
  p_embedding_kind text,
  p_left_embedding_id bigint,
  p_right_embedding_id bigint,
  p_left_source_table text,
  p_left_source_key text,
  p_right_source_table text,
  p_right_source_key text,
  p_cosine_distance double precision,
  p_cosine_similarity double precision,
  p_rank integer,
  p_evidence jsonb
)
returns bigint
language plpgsql
as $$
declare
  v_pair_id bigint;
  v_left_source_mac text;
  v_left_sensor_id text;
  v_left_location_id text;
  v_left_observed_at timestamptz;
  v_right_source_mac text;
  v_right_sensor_id text;
  v_right_location_id text;
  v_right_observed_at timestamptz;
begin
  select pair_id
    into v_pair_id
  from vec_similarity_pair_meta
  where pair_kind = p_pair_kind
    and embedding_model = p_embedding_model
    and embedding_kind = p_embedding_kind
    and left_embedding_id = p_left_embedding_id
    and right_embedding_id = p_right_embedding_id
  for update;

  if v_pair_id is null then
    if exists (
      select 1
      from information_schema.columns
      where table_schema = current_schema()
        and table_name = 'vec_similarity_pairs'
        and column_name = 'pair_kind'
    ) then
      select source_mac, source_sensor_id, source_location_id, source_observed_at
        into v_left_source_mac, v_left_sensor_id, v_left_location_id, v_left_observed_at
      from vec_embedding_sources
      where embedding_id = p_left_embedding_id;

      select source_mac, source_sensor_id, source_location_id, source_observed_at
        into v_right_source_mac, v_right_sensor_id, v_right_location_id, v_right_observed_at
      from vec_embedding_sources
      where embedding_id = p_right_embedding_id;

      execute $legacy_insert$
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
        values (
          $1, $2, $3, $4, $5,
          $6, $7, $8, $9, $10, $11,
          $12, $13, $14, $15, $16, $17,
          $18, $19, $20, $21,
          now(), now(), now()
        )
        returning pair_id
      $legacy_insert$
      into v_pair_id
      using
        p_pair_kind, p_embedding_model, p_embedding_kind,
        p_left_embedding_id, p_right_embedding_id,
        p_left_source_table, p_left_source_key, v_left_source_mac,
        v_left_sensor_id, v_left_location_id, v_left_observed_at,
        p_right_source_table, p_right_source_key, v_right_source_mac,
        v_right_sensor_id, v_right_location_id, v_right_observed_at,
        p_cosine_distance, p_cosine_similarity, p_rank,
        coalesce(p_evidence, '{}'::jsonb);
    else
      insert into vec_similarity_pairs (
        left_embedding_id, right_embedding_id, cosine_distance,
        cosine_similarity, rank, computed_at, created_at, updated_at
      )
      values (
        p_left_embedding_id, p_right_embedding_id, p_cosine_distance,
        p_cosine_similarity, p_rank, now(), now(), now()
      )
      returning pair_id into v_pair_id;
    end if;

    insert into vec_similarity_pair_meta (
      pair_id, pair_kind, embedding_model, embedding_kind,
      left_source_table, left_source_key, right_source_table, right_source_key,
      evidence, left_embedding_id, right_embedding_id
    )
    values (
      v_pair_id, p_pair_kind, p_embedding_model, p_embedding_kind,
      p_left_source_table, p_left_source_key, p_right_source_table, p_right_source_key,
      coalesce(p_evidence, '{}'::jsonb), p_left_embedding_id, p_right_embedding_id
    )
    on conflict (pair_id) do update set
      pair_kind = excluded.pair_kind,
      embedding_model = excluded.embedding_model,
      embedding_kind = excluded.embedding_kind,
      left_source_table = excluded.left_source_table,
      left_source_key = excluded.left_source_key,
      right_source_table = excluded.right_source_table,
      right_source_key = excluded.right_source_key,
      evidence = excluded.evidence,
      left_embedding_id = excluded.left_embedding_id,
      right_embedding_id = excluded.right_embedding_id;
  else
    update vec_similarity_pairs
       set cosine_distance = p_cosine_distance,
           cosine_similarity = p_cosine_similarity,
           rank = p_rank,
           computed_at = now(),
           updated_at = now()
     where pair_id = v_pair_id;

    if exists (
      select 1
      from information_schema.columns
      where table_schema = current_schema()
        and table_name = 'vec_similarity_pairs'
        and column_name = 'pair_kind'
    ) then
      execute $legacy_update$
        update vec_similarity_pairs
           set left_source_table = $1,
               left_source_key = $2,
               right_source_table = $3,
               right_source_key = $4,
               evidence = $5
         where pair_id = $6
      $legacy_update$ using
        p_left_source_table, p_left_source_key,
        p_right_source_table, p_right_source_key,
        coalesce(p_evidence, '{}'::jsonb), v_pair_id;
    end if;

    update vec_similarity_pair_meta
       set left_source_table = p_left_source_table,
           left_source_key = p_left_source_key,
           right_source_table = p_right_source_table,
           right_source_key = p_right_source_key,
           evidence = coalesce(p_evidence, '{}'::jsonb)
     where pair_id = v_pair_id;
  end if;

  return v_pair_id;
end;
$$;
