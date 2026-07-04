-- object: vec_fuse_device_identities
-- folder: functions
-- depends_on: device_identity_clusters, vec_similarity_pairs
create or replace function vec_fuse_device_identities(
  p_behaviour_distance_threshold double precision default 0.12,
  p_time_overlap_minutes integer default 30,
  p_timing_distance_threshold double precision default 0.05
)
returns integer
language plpgsql
as $$
declare
  v_pair record;
  v_cluster_ids bigint[];
  v_target_cluster_id bigint;
  v_merged_macs text[];
  v_count integer := 0;
  v_rows integer := 0;
begin
  if not vec_try_begin_job('vec_fuse_device_identities') then
    return 0;
  end if;

  for v_pair in
    with behaviour_pairs as (
      select
        lower(sp.left_source_mac) as mac_a,
        lower(sp.right_source_mac) as mac_b
      from vec_similarity_pairs sp
      join vec_behaviour_snapshots left_snapshot
        on left_snapshot.snapshot_id::text = sp.left_source_key
      join vec_behaviour_snapshots right_snapshot
        on right_snapshot.snapshot_id::text = sp.right_source_key
      where sp.pair_kind = 'device_device'
        and sp.embedding_kind = 'behaviour_window'
        and sp.cosine_distance <= p_behaviour_distance_threshold
        and sp.computed_at >= now() - interval '2 hours'
        and sp.left_source_mac is not null
        and sp.right_source_mac is not null
        and lower(sp.left_source_mac) <> lower(sp.right_source_mac)
        and left_snapshot.sensor_id is not distinct from right_snapshot.sensor_id
        and left_snapshot.location_id is not distinct from right_snapshot.location_id
        and abs(extract(epoch from (left_snapshot.window_start - right_snapshot.window_start))) <= p_time_overlap_minutes * 60
    ),
    timing_pairs as (
      select
        lower(sp.left_source_mac) as mac_a,
        lower(sp.right_source_mac) as mac_b
      from vec_similarity_pairs sp
      join vec_timing_profiles left_profile
        on left_profile.profile_id::text = sp.left_source_key
      join vec_timing_profiles right_profile
        on right_profile.profile_id::text = sp.right_source_key
      where sp.pair_kind = 'timing_timing'
        and sp.embedding_kind = 'timing_profile'
        and sp.cosine_distance <= p_timing_distance_threshold
        and sp.computed_at >= now() - interval '2 hours'
        and sp.left_source_mac is not null
        and sp.right_source_mac is not null
        and lower(sp.left_source_mac) <> lower(sp.right_source_mac)
        and left_profile.sensor_id is not distinct from right_profile.sensor_id
        and left_profile.location_id is not distinct from right_profile.location_id
    ),
    candidate_pairs as (
      select mac_a, mac_b from behaviour_pairs
      union
      select mac_a, mac_b from timing_pairs
    ),
    normalized_pairs as (
      select distinct
        least(mac_a, mac_b) as mac_a,
        greatest(mac_a, mac_b) as mac_b
      from candidate_pairs
      where mac_a ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'
        and mac_b ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'
        and (get_byte(decode(split_part(mac_a, ':', 1), 'hex'), 0) & 2) = 2
        and (get_byte(decode(split_part(mac_b, ':', 1), 'hex'), 0) & 2) = 2
    )
    select mac_a, mac_b
    from normalized_pairs
  loop
    select array_agg(cluster_id order by cluster_id)
      into v_cluster_ids
    from device_identity_clusters
    where exists (
      select 1
      from unnest(mac_ids) as cluster_mac(mac)
      where lower(cluster_mac.mac) in (v_pair.mac_a, v_pair.mac_b)
    );

    if v_cluster_ids is null or cardinality(v_cluster_ids) = 0 then
      insert into device_identity_clusters (
        mac_ids,
        size,
        first_seen,
        last_seen,
        created_at,
        updated_at
      )
      values (
        array[v_pair.mac_a, v_pair.mac_b],
        2,
        now(),
        now(),
        now(),
        now()
      );
      get diagnostics v_rows = row_count;
      v_count := v_count + v_rows;
    else
      v_target_cluster_id := v_cluster_ids[1];

      select array_agg(mac order by mac)
        into v_merged_macs
      from (
        select distinct lower(cluster_mac.mac) as mac
        from device_identity_clusters
        cross join lateral unnest(mac_ids) as cluster_mac(mac)
        where cluster_id = any(v_cluster_ids)
        union
        select v_pair.mac_a
        union
        select v_pair.mac_b
      ) merged;

      update device_identity_clusters target
         set mac_ids = v_merged_macs,
             size = cardinality(v_merged_macs),
             first_seen = (
               select min(first_seen)
               from device_identity_clusters
               where cluster_id = any(v_cluster_ids)
             ),
             last_seen = now(),
             updated_at = now()
       where target.cluster_id = v_target_cluster_id;

      get diagnostics v_rows = row_count;
      v_count := v_count + v_rows;

      if cardinality(v_cluster_ids) > 1 then
        delete from device_identity_clusters
        where cluster_id = any(v_cluster_ids[2:cardinality(v_cluster_ids)]);

        get diagnostics v_rows = row_count;
        v_count := v_count + v_rows;
      end if;
    end if;
  end loop;

  perform vec_finish_job('vec_fuse_device_identities');
  return v_count;
exception when others then
  perform vec_finish_job('vec_fuse_device_identities');
  raise;
end;
$$;
