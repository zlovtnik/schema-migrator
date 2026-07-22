-- object: vec_update_device_centroids
-- folder: functions
-- depends_on: device_identity_clusters, vec_embeddings
create or replace function vec_update_device_centroids(
  p_model text default 'nomic-embed-text-v2-moe',
  p_min_samples integer default 10,
  p_window interval default interval '7 days'
)
returns integer
language plpgsql
as $$
declare
  v_count integer := 0;
begin
  if not vec_try_begin_job('vec_update_device_centroids') then
    return 0;
  end if;

  with recent_embeddings as materialized (
    select
      lower(source_mac) as source_mac,
      embedding::vector(768) as emb,
      embedded_at
    from vec_embeddings_expanded
    where embedding_kind = 'event'
      and embedding_model = p_model
      and embedding_dimensions = 768
      and embedded_at >= now() - p_window
      and source_mac is not null
  ),
  cluster_centroids as (
    select
      dic.cluster_id,
      avg(recent.emb)::vector(768) as centroid,
      count(*)::integer as sample_count,
      min(recent.embedded_at) as first_seen_at,
      max(recent.embedded_at) as last_seen_at
    from device_identity_clusters dic
    join recent_embeddings recent
      on exists (
        select 1
        from unnest(dic.mac_ids) as cluster_mac(mac)
        where lower(cluster_mac.mac) = recent.source_mac
      )
    group by dic.cluster_id
    having count(*) >= p_min_samples
  ),
  updated as (
    update device_identity_clusters dic
       set embedding_centroid = cluster_centroids.centroid,
           centroid_updated_at = now(),
           centroid_sample_count = cluster_centroids.sample_count,
           first_seen = least(dic.first_seen, cluster_centroids.first_seen_at),
           last_seen = greatest(dic.last_seen, cluster_centroids.last_seen_at),
           updated_at = now()
      from cluster_centroids
     where dic.cluster_id = cluster_centroids.cluster_id
     returning 1
  ),
  unclustered_mac_centroids as (
    select
      recent.source_mac,
      avg(recent.emb)::vector(768) as centroid,
      count(*)::integer as sample_count,
      min(recent.embedded_at) as first_seen_at,
      max(recent.embedded_at) as last_seen_at
    from recent_embeddings recent
    where not exists (
      select 1
      from device_identity_clusters dic
      where exists (
        select 1
        from unnest(dic.mac_ids) as cluster_mac(mac)
        where lower(cluster_mac.mac) = recent.source_mac
      )
    )
    group by recent.source_mac
    having count(*) >= p_min_samples
  ),
  inserted as (
    insert into device_identity_clusters (
      mac_ids,
      size,
      embedding_centroid,
      centroid_updated_at,
      centroid_sample_count,
      first_seen,
      last_seen,
      created_at,
      updated_at
    )
    select
      array[source_mac],
      1,
      centroid,
      now(),
      sample_count,
      first_seen_at,
      last_seen_at,
      now(),
      now()
    from unclustered_mac_centroids
    returning 1
  )
  select count(*)::integer into v_count
  from (
    select 1 from updated
    union all
    select 1 from inserted
  ) changed;

  perform vec_finish_job('vec_update_device_centroids');
  return v_count;
exception when others then
  perform vec_finish_job('vec_update_device_centroids');
  raise;
end;
$$;
