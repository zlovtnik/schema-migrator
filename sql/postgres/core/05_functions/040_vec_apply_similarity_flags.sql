-- object: vec_apply_similarity_flags
-- folder: functions
-- depends_on: vec_similarity_pairs, wireless_frames, wireless_shadow_alerts
create or replace function vec_apply_similarity_flags(
  p_model text default 'nomic-embed-text-v2-moe',
  p_event_dup_distance_threshold double precision default 0.05,
  p_behaviour_similarity_threshold double precision default 0.88
)
returns integer
language plpgsql
as $$
declare
  v_total integer := 0;
  v_count integer := 0;
begin
  if not vec_try_begin_job('vec_apply_similarity_flags') then
    return 0;
  end if;

  update wireless_frames target
     set dedupe_or_replay_suspect = true,
         updated_at = now()
   where target.dedupe_key in (
     select left_source_key
     from vec_similarity_pairs
     where pair_kind = 'event_event'
       and embedding_model = p_model
       and embedding_kind = 'event'
       and cosine_distance <= p_event_dup_distance_threshold
     union
     select right_source_key
     from vec_similarity_pairs
     where pair_kind = 'event_event'
       and embedding_model = p_model
       and embedding_kind = 'event'
       and cosine_distance <= p_event_dup_distance_threshold
   )
     and not coalesce(target.dedupe_or_replay_suspect, false);

  get diagnostics v_count = row_count;
  v_total := v_total + v_count;

  insert into wireless_shadow_alerts (
    source_mac, first_occurred_at, last_occurred_at, occurrence_count,
    destination_bssid, ssid, sensor_id, location_id, signal_dbm,
    reason, evidence, resolved_at, created_at, updated_at
  )
  select distinct on (right_snapshot.source_mac)
    right_snapshot.source_mac,
    least(left_snapshot.window_start, right_snapshot.window_start),
    greatest(left_snapshot.window_end, right_snapshot.window_end),
    1,
    null,
    null,
    right_snapshot.sensor_id,
    right_snapshot.location_id,
    right_snapshot.signal_avg_dbm::integer,
    'mac_rotation_suspected',
    jsonb_build_object(
      'matched_mac', left_snapshot.source_mac,
      'behaviour_similarity', round(pair.cosine_similarity::numeric, 4),
      'left_snapshot_id', left_snapshot.snapshot_id,
      'right_snapshot_id', right_snapshot.snapshot_id,
      'pair_id', pair.pair_id
    ),
    null,
    now(),
    now()
  from vec_similarity_pairs pair
  join vec_behaviour_snapshots left_snapshot on left_snapshot.snapshot_id::text = pair.left_source_key
  join vec_behaviour_snapshots right_snapshot on right_snapshot.snapshot_id::text = pair.right_source_key
  where pair.pair_kind = 'device_device'
    and pair.embedding_model = p_model
    and pair.embedding_kind = 'behaviour_window'
    and pair.cosine_similarity >= p_behaviour_similarity_threshold
    and left_snapshot.source_mac ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'
    and right_snapshot.source_mac ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'
    and left_snapshot.source_mac <> right_snapshot.source_mac
  order by right_snapshot.source_mac, pair.cosine_similarity desc
  on conflict (source_mac) do update set
    last_occurred_at = greatest(wireless_shadow_alerts.last_occurred_at, excluded.last_occurred_at),
    occurrence_count = coalesce(wireless_shadow_alerts.occurrence_count, 0) + case
      when excluded.last_occurred_at > wireless_shadow_alerts.last_occurred_at
      then excluded.occurrence_count
      else 0
    end,
    sensor_id = excluded.sensor_id,
    location_id = excluded.location_id,
    signal_dbm = excluded.signal_dbm,
    reason = excluded.reason,
    evidence = excluded.evidence,
    updated_at = now();

  get diagnostics v_count = row_count;
  v_total := v_total + v_count;

  perform vec_finish_job('vec_apply_similarity_flags');
  return v_total;
exception when others then
  perform vec_finish_job('vec_apply_similarity_flags');
  raise;
end;
$$;
