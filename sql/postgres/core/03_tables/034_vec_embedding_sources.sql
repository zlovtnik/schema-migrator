-- object: vec_embedding_sources
-- folder: tables
-- depends_on: vec_embeddings
create table if not exists vec_embedding_sources (
  embedding_id bigint primary key references vec_embeddings(embedding_id) on delete cascade,
  source_table text not null,
  source_key text not null,
  source_observed_at timestamptz,
  source_stream_name text,
  source_sensor_id text,
  source_location_id text,
  source_mac text,
  embedding_model text not null,
  embedding_kind text not null,
  constraint vec_embedding_sources_unique
    unique (source_table, source_key, embedding_model, embedding_kind)
);

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'vec_embeddings'
      and column_name = 'source_table'
  ) then
    execute $backfill$
      insert into vec_embedding_sources (
        embedding_id, source_table, source_key, source_observed_at,
        source_stream_name, source_sensor_id, source_location_id, source_mac,
        embedding_model, embedding_kind
      )
      select
        embedding_id, source_table, source_key, source_observed_at,
        source_stream_name, source_sensor_id, source_location_id, source_mac,
        embedding_model, embedding_kind
      from vec_embeddings
      on conflict (embedding_id) do update set
        source_table = excluded.source_table,
        source_key = excluded.source_key,
        source_observed_at = excluded.source_observed_at,
        source_stream_name = excluded.source_stream_name,
        source_sensor_id = excluded.source_sensor_id,
        source_location_id = excluded.source_location_id,
        source_mac = excluded.source_mac,
        embedding_model = excluded.embedding_model,
        embedding_kind = excluded.embedding_kind
    $backfill$;
  end if;
end;
$$;

create or replace function vec_embeddings_legacy_to_source()
returns trigger
language plpgsql
as $$
declare
  legacy jsonb := to_jsonb(new);
begin
  if pg_trigger_depth() > 1 then
    return new;
  end if;

  if legacy ? 'source_table' then
    insert into vec_embedding_sources (
      embedding_id, source_table, source_key, source_observed_at,
      source_stream_name, source_sensor_id, source_location_id, source_mac,
      embedding_model, embedding_kind
    )
    values (
      new.embedding_id,
      legacy->>'source_table',
      legacy->>'source_key',
      (legacy->>'source_observed_at')::timestamptz,
      legacy->>'source_stream_name',
      legacy->>'source_sensor_id',
      legacy->>'source_location_id',
      legacy->>'source_mac',
      new.embedding_model,
      new.embedding_kind
    )
    on conflict (embedding_id) do update set
      source_table = excluded.source_table,
      source_key = excluded.source_key,
      source_observed_at = excluded.source_observed_at,
      source_stream_name = excluded.source_stream_name,
      source_sensor_id = excluded.source_sensor_id,
      source_location_id = excluded.source_location_id,
      source_mac = excluded.source_mac,
      embedding_model = excluded.embedding_model,
      embedding_kind = excluded.embedding_kind;
  else
    update vec_embedding_sources
       set embedding_model = new.embedding_model,
           embedding_kind = new.embedding_kind
     where embedding_id = new.embedding_id;
  end if;
  return new;
end;
$$;

create or replace function vec_embedding_sources_to_legacy()
returns trigger
language plpgsql
as $$
begin
  if pg_trigger_depth() > 1 or not exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'vec_embeddings'
      and column_name = 'source_table'
  ) then
    return new;
  end if;

  execute $sync$
    update vec_embeddings set
      source_table = $1, source_key = $2, source_observed_at = $3,
      source_stream_name = $4, source_sensor_id = $5,
      source_location_id = $6, source_mac = $7
    where embedding_id = $8
  $sync$ using
    new.source_table, new.source_key, new.source_observed_at,
    new.source_stream_name, new.source_sensor_id,
    new.source_location_id, new.source_mac, new.embedding_id;
  return new;
end;
$$;

drop trigger if exists vec_embeddings_legacy_to_source on vec_embeddings;
create trigger vec_embeddings_legacy_to_source
after insert or update on vec_embeddings
for each row execute function vec_embeddings_legacy_to_source();

drop trigger if exists vec_embedding_sources_to_legacy on vec_embedding_sources;
create trigger vec_embedding_sources_to_legacy
after insert or update on vec_embedding_sources
for each row execute function vec_embedding_sources_to_legacy();
