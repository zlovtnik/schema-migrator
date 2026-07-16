-- object: vec_similarity_pair_meta
-- folder: tables
-- depends_on: vec_similarity_pairs
create table if not exists vec_similarity_pair_meta (
  pair_id bigint primary key references vec_similarity_pairs(pair_id) on delete cascade,
  pair_kind text not null,
  embedding_model text not null,
  embedding_kind text not null,
  left_source_table text not null,
  left_source_key text not null,
  right_source_table text not null,
  right_source_key text not null,
  evidence jsonb not null default '{}'::jsonb,
  left_embedding_id bigint not null,
  right_embedding_id bigint not null,
  constraint vec_similarity_pair_meta_kind_chk
    check (pair_kind in ('event_event', 'device_device', 'cross_sensor', 'sequence_sequence', 'timing_timing')),
  constraint vec_similarity_pair_meta_unique
    unique (pair_kind, embedding_model, embedding_kind, left_embedding_id, right_embedding_id)
);

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'vec_similarity_pairs'
      and column_name = 'pair_kind'
  ) then
    execute $backfill$
      insert into vec_similarity_pair_meta (
        pair_id, pair_kind, embedding_model, embedding_kind,
        left_source_table, left_source_key, right_source_table, right_source_key,
        evidence, left_embedding_id, right_embedding_id
      )
      select
        pair_id, pair_kind, embedding_model, embedding_kind,
        left_source_table, left_source_key, right_source_table, right_source_key,
        evidence, left_embedding_id, right_embedding_id
      from vec_similarity_pairs
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
        right_embedding_id = excluded.right_embedding_id
    $backfill$;
  end if;
end;
$$;

create or replace function vec_similarity_pairs_legacy_to_meta()
returns trigger
language plpgsql
as $$
declare
  legacy jsonb := to_jsonb(new);
begin
  if pg_trigger_depth() > 1 or not (legacy ? 'pair_kind') then
    return new;
  end if;

  insert into vec_similarity_pair_meta (
    pair_id, pair_kind, embedding_model, embedding_kind,
    left_source_table, left_source_key, right_source_table, right_source_key,
    evidence, left_embedding_id, right_embedding_id
  )
  values (
    new.pair_id,
    legacy->>'pair_kind',
    legacy->>'embedding_model',
    legacy->>'embedding_kind',
    legacy->>'left_source_table',
    legacy->>'left_source_key',
    legacy->>'right_source_table',
    legacy->>'right_source_key',
    coalesce(legacy->'evidence', '{}'::jsonb),
    new.left_embedding_id,
    new.right_embedding_id
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
  return new;
end;
$$;

create or replace function vec_similarity_pair_meta_to_legacy()
returns trigger
language plpgsql
as $$
begin
  if pg_trigger_depth() > 1 or not exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'vec_similarity_pairs'
      and column_name = 'pair_kind'
  ) then
    return new;
  end if;

  execute $sync$
    update vec_similarity_pairs set
      pair_kind = $1, embedding_model = $2, embedding_kind = $3,
      left_source_table = $4, left_source_key = $5,
      left_source_mac = (select source_mac from vec_embedding_sources where embedding_id = $9),
      left_sensor_id = (select source_sensor_id from vec_embedding_sources where embedding_id = $9),
      left_location_id = (select source_location_id from vec_embedding_sources where embedding_id = $9),
      left_observed_at = (select source_observed_at from vec_embedding_sources where embedding_id = $9),
      right_source_table = $6, right_source_key = $7,
      right_source_mac = (select source_mac from vec_embedding_sources where embedding_id = $10),
      right_sensor_id = (select source_sensor_id from vec_embedding_sources where embedding_id = $10),
      right_location_id = (select source_location_id from vec_embedding_sources where embedding_id = $10),
      right_observed_at = (select source_observed_at from vec_embedding_sources where embedding_id = $10),
      evidence = $8
    where pair_id = $11
  $sync$ using
    new.pair_kind, new.embedding_model, new.embedding_kind,
    new.left_source_table, new.left_source_key,
    new.right_source_table, new.right_source_key, new.evidence,
    new.left_embedding_id, new.right_embedding_id, new.pair_id;
  return new;
end;
$$;

drop trigger if exists vec_similarity_pairs_legacy_to_meta on vec_similarity_pairs;
create trigger vec_similarity_pairs_legacy_to_meta
after insert or update on vec_similarity_pairs
for each row execute function vec_similarity_pairs_legacy_to_meta();

drop trigger if exists vec_similarity_pair_meta_to_legacy on vec_similarity_pair_meta;
create trigger vec_similarity_pair_meta_to_legacy
after insert or update on vec_similarity_pair_meta
for each row execute function vec_similarity_pair_meta_to_legacy();
