-- object: vec_behaviour_snapshot_stats
-- folder: tables
-- depends_on: vec_behaviour_snapshots
create table if not exists vec_behaviour_snapshot_stats (
  snapshot_id bigint primary key references vec_behaviour_snapshots(snapshot_id) on delete cascade,
  protocol_mix jsonb not null default '{}'::jsonb,
  frame_type_distribution jsonb not null default '{}'::jsonb,
  signal_min_dbm integer,
  signal_max_dbm integer,
  signal_avg_dbm numeric(8,2),
  retry_count bigint not null default 0,
  protected_count bigint not null default 0,
  unprotected_count bigint not null default 0,
  unique_bssid_count bigint not null default 0,
  mac_rotation_indicators jsonb not null default '{}'::jsonb
);

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'vec_behaviour_snapshots'
      and column_name = 'protocol_mix'
  ) then
    execute $backfill$
      insert into vec_behaviour_snapshot_stats (
        snapshot_id, protocol_mix, frame_type_distribution,
        signal_min_dbm, signal_max_dbm, signal_avg_dbm,
        retry_count, protected_count, unprotected_count,
        unique_bssid_count, mac_rotation_indicators
      )
      select
        snapshot_id, protocol_mix, frame_type_distribution,
        signal_min_dbm, signal_max_dbm, signal_avg_dbm,
        retry_count, protected_count, unprotected_count,
        unique_bssid_count, mac_rotation_indicators
      from vec_behaviour_snapshots
      on conflict (snapshot_id) do update set
        protocol_mix = excluded.protocol_mix,
        frame_type_distribution = excluded.frame_type_distribution,
        signal_min_dbm = excluded.signal_min_dbm,
        signal_max_dbm = excluded.signal_max_dbm,
        signal_avg_dbm = excluded.signal_avg_dbm,
        retry_count = excluded.retry_count,
        protected_count = excluded.protected_count,
        unprotected_count = excluded.unprotected_count,
        unique_bssid_count = excluded.unique_bssid_count,
        mac_rotation_indicators = excluded.mac_rotation_indicators
    $backfill$;
  end if;
end;
$$;

create or replace function vec_behaviour_snapshots_legacy_to_stats()
returns trigger
language plpgsql
as $$
declare
  legacy jsonb := to_jsonb(new);
begin
  if pg_trigger_depth() > 1 or not (legacy ? 'protocol_mix') then
    return new;
  end if;

  insert into vec_behaviour_snapshot_stats (
    snapshot_id, protocol_mix, frame_type_distribution,
    signal_min_dbm, signal_max_dbm, signal_avg_dbm,
    retry_count, protected_count, unprotected_count,
    unique_bssid_count, mac_rotation_indicators
  )
  values (
    new.snapshot_id,
    coalesce(legacy->'protocol_mix', '{}'::jsonb),
    coalesce(legacy->'frame_type_distribution', '{}'::jsonb),
    (legacy->>'signal_min_dbm')::integer,
    (legacy->>'signal_max_dbm')::integer,
    (legacy->>'signal_avg_dbm')::numeric,
    coalesce((legacy->>'retry_count')::bigint, 0),
    coalesce((legacy->>'protected_count')::bigint, 0),
    coalesce((legacy->>'unprotected_count')::bigint, 0),
    coalesce((legacy->>'unique_bssid_count')::bigint, 0),
    coalesce(legacy->'mac_rotation_indicators', '{}'::jsonb)
  )
  on conflict (snapshot_id) do update set
    protocol_mix = excluded.protocol_mix,
    frame_type_distribution = excluded.frame_type_distribution,
    signal_min_dbm = excluded.signal_min_dbm,
    signal_max_dbm = excluded.signal_max_dbm,
    signal_avg_dbm = excluded.signal_avg_dbm,
    retry_count = excluded.retry_count,
    protected_count = excluded.protected_count,
    unprotected_count = excluded.unprotected_count,
    unique_bssid_count = excluded.unique_bssid_count,
    mac_rotation_indicators = excluded.mac_rotation_indicators;
  return new;
end;
$$;

create or replace function vec_behaviour_snapshot_stats_to_legacy()
returns trigger
language plpgsql
as $$
begin
  if pg_trigger_depth() > 1 or not exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'vec_behaviour_snapshots'
      and column_name = 'protocol_mix'
  ) then
    return new;
  end if;

  execute $sync$
    update vec_behaviour_snapshots set
      protocol_mix = $1, frame_type_distribution = $2,
      signal_min_dbm = $3, signal_max_dbm = $4, signal_avg_dbm = $5,
      retry_count = $6, protected_count = $7, unprotected_count = $8,
      unique_bssid_count = $9, mac_rotation_indicators = $10
    where snapshot_id = $11
  $sync$ using
    new.protocol_mix, new.frame_type_distribution,
    new.signal_min_dbm, new.signal_max_dbm, new.signal_avg_dbm,
    new.retry_count, new.protected_count, new.unprotected_count,
    new.unique_bssid_count, new.mac_rotation_indicators, new.snapshot_id;
  return new;
end;
$$;

drop trigger if exists vec_behaviour_snapshots_legacy_to_stats on vec_behaviour_snapshots;
create trigger vec_behaviour_snapshots_legacy_to_stats
after insert or update on vec_behaviour_snapshots
for each row execute function vec_behaviour_snapshots_legacy_to_stats();

drop trigger if exists vec_behaviour_snapshot_stats_to_legacy on vec_behaviour_snapshot_stats;
create trigger vec_behaviour_snapshot_stats_to_legacy
after insert or update on vec_behaviour_snapshot_stats
for each row execute function vec_behaviour_snapshot_stats_to_legacy();
