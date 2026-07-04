-- object: device_identity_clusters indexes
-- folder: indexes
-- depends_on: device_identity_clusters
-- GIN index for fast membership lookups via ANY(mac_ids)
create index if not exists idx_device_identity_clusters_mac_ids
  on device_identity_clusters using gin (mac_ids);
