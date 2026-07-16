-- object: vec_timing_profile_stats
-- folder: tables
-- depends_on: vec_timing_profiles
create table if not exists vec_timing_profile_stats (
  profile_id bigint primary key references vec_timing_profiles(profile_id) on delete cascade,
  tsft_p50_us numeric,
  tsft_p95_us numeric,
  tsft_jitter numeric,
  wall_p50_ms numeric,
  wall_jitter_ms numeric,
  beacon_interval_median_ms numeric,
  beacon_jitter_ms numeric
);

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'vec_timing_profiles'
      and column_name = 'tsft_p50_us'
  ) then
    execute $backfill$
      insert into vec_timing_profile_stats (
        profile_id, tsft_p50_us, tsft_p95_us, tsft_jitter,
        wall_p50_ms, wall_jitter_ms, beacon_interval_median_ms, beacon_jitter_ms
      )
      select
        profile_id, tsft_p50_us, tsft_p95_us, tsft_jitter,
        wall_p50_ms, wall_jitter_ms, beacon_interval_median_ms, beacon_jitter_ms
      from vec_timing_profiles
      on conflict (profile_id) do update set
        tsft_p50_us = excluded.tsft_p50_us,
        tsft_p95_us = excluded.tsft_p95_us,
        tsft_jitter = excluded.tsft_jitter,
        wall_p50_ms = excluded.wall_p50_ms,
        wall_jitter_ms = excluded.wall_jitter_ms,
        beacon_interval_median_ms = excluded.beacon_interval_median_ms,
        beacon_jitter_ms = excluded.beacon_jitter_ms
    $backfill$;
  end if;
end;
$$;

create or replace function vec_timing_profiles_legacy_to_stats()
returns trigger
language plpgsql
as $$
declare
  legacy jsonb := to_jsonb(new);
begin
  if pg_trigger_depth() > 1 or not (legacy ? 'tsft_p50_us') then
    return new;
  end if;

  insert into vec_timing_profile_stats (
    profile_id, tsft_p50_us, tsft_p95_us, tsft_jitter,
    wall_p50_ms, wall_jitter_ms, beacon_interval_median_ms, beacon_jitter_ms
  )
  values (
    new.profile_id,
    (legacy->>'tsft_p50_us')::numeric,
    (legacy->>'tsft_p95_us')::numeric,
    (legacy->>'tsft_jitter')::numeric,
    (legacy->>'wall_p50_ms')::numeric,
    (legacy->>'wall_jitter_ms')::numeric,
    (legacy->>'beacon_interval_median_ms')::numeric,
    (legacy->>'beacon_jitter_ms')::numeric
  )
  on conflict (profile_id) do update set
    tsft_p50_us = excluded.tsft_p50_us,
    tsft_p95_us = excluded.tsft_p95_us,
    tsft_jitter = excluded.tsft_jitter,
    wall_p50_ms = excluded.wall_p50_ms,
    wall_jitter_ms = excluded.wall_jitter_ms,
    beacon_interval_median_ms = excluded.beacon_interval_median_ms,
    beacon_jitter_ms = excluded.beacon_jitter_ms;
  return new;
end;
$$;

create or replace function vec_timing_profile_stats_to_legacy()
returns trigger
language plpgsql
as $$
begin
  if pg_trigger_depth() > 1 or not exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'vec_timing_profiles'
      and column_name = 'tsft_p50_us'
  ) then
    return new;
  end if;

  execute $sync$
    update vec_timing_profiles set
      tsft_p50_us = $1, tsft_p95_us = $2, tsft_jitter = $3,
      wall_p50_ms = $4, wall_jitter_ms = $5,
      beacon_interval_median_ms = $6, beacon_jitter_ms = $7
    where profile_id = $8
  $sync$ using
    new.tsft_p50_us, new.tsft_p95_us, new.tsft_jitter,
    new.wall_p50_ms, new.wall_jitter_ms,
    new.beacon_interval_median_ms, new.beacon_jitter_ms, new.profile_id;
  return new;
end;
$$;

drop trigger if exists vec_timing_profiles_legacy_to_stats on vec_timing_profiles;
create trigger vec_timing_profiles_legacy_to_stats
after insert or update on vec_timing_profiles
for each row execute function vec_timing_profiles_legacy_to_stats();

drop trigger if exists vec_timing_profile_stats_to_legacy on vec_timing_profile_stats;
create trigger vec_timing_profile_stats_to_legacy
after insert or update on vec_timing_profile_stats
for each row execute function vec_timing_profile_stats_to_legacy();
