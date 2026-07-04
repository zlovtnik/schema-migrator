-- object: vector detector support indexes
-- folder: indexes
-- depends_on: vec_rf_sensor_locations, vec_dns_policy, vec_dns_resolver_ledger, vec_alerts
create index if not exists vec_rf_sensor_locations_enabled_idx
  on vec_rf_sensor_locations (sensor_id, location_id)
  where enabled;

create index if not exists vec_dns_policy_secure_required_idx
  on vec_dns_policy (wg_pubkey)
  where policy = 'secure_required';

create index if not exists vec_dns_resolver_ledger_pubkey_time_idx
  on vec_dns_resolver_ledger (wg_pubkey, observed_at desc);

create index if not exists vec_dns_resolver_ledger_query_hash_idx
  on vec_dns_resolver_ledger (wg_pubkey, query_name_hash, observed_at desc)
  where query_name_hash is not null;

create index if not exists vec_alerts_metadata_wg_pubkey_idx
  on vec_alerts ((metadata->>'wg_pubkey'))
  where metadata ? 'wg_pubkey';

create index if not exists vec_alerts_metadata_cluster_id_idx
  on vec_alerts ((metadata->>'cluster_id'))
  where metadata ? 'cluster_id';

create index if not exists vec_alerts_metadata_session_key_idx
  on vec_alerts ((metadata->>'session_key'))
  where metadata ? 'session_key';
