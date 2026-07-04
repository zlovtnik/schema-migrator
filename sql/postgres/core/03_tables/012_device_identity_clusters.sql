-- object: device_identity_clusters
-- folder: tables
-- depends_on: devices
-- Track 2: MAC-rotated devices tracked as a single identity cluster.
-- Multiple devices (different mac_ids) that belong to the same physical
-- device are grouped into one cluster. The mac_ids array stores all
-- associated MACs, and size is a denormalized count for fast querying.
create table if not exists device_identity_clusters (
  cluster_id    bigserial primary key,
  cluster_name  text,                        -- optional human-readable label
  mac_ids       text[] not null default '{}', -- array of associated mac_ids
  size          integer not null default 1,   -- number of MACs in the cluster
  embedding_centroid vector(768),
  centroid_updated_at timestamptz,
  centroid_sample_count integer not null default 0,
  first_seen    timestamptz not null default now(),
  last_seen     timestamptz not null default now(),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

alter table device_identity_clusters
  add column if not exists embedding_centroid vector(768),
  add column if not exists centroid_updated_at timestamptz,
  add column if not exists centroid_sample_count integer not null default 0;
