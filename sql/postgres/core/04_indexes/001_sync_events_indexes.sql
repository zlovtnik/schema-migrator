-- object: sync plane indexes
-- folder: indexes
-- depends_on: sync_events, sync_jobs, sync_batches, sync_errors, sync_backlog, wireless_authorized_networks, wireless_clients, wireless_shadow_alerts, devices
create index if not exists sync_events_status_idx on sync_events (status, observed_at);

create index if not exists sync_events_stream_idx on sync_events (stream_name, observed_at);

create index if not exists sync_events_ready_idx on sync_events (status, stream_name, observed_at) where status in ('pending', 'failed');

create index if not exists sync_events_wireless_observed_idx
  on sync_events (observed_at desc)
  where stream_name = 'wireless.audit'
    and status = 'batched';

create index if not exists sync_events_processing_idx on sync_events (updated_at) where status = 'processing';

create index if not exists sync_jobs_stream_name_idx on sync_jobs (stream_name);

create index if not exists sync_jobs_status_created_at_idx on sync_jobs (status, created_at);

create index if not exists sync_batches_job_batch_no_idx on sync_batches (job_id, batch_no);

create index if not exists sync_batches_status_idx on sync_batches (status);

create index if not exists sync_batches_pending_idx on sync_batches (status, batch_id) where status = 'pending';

create index if not exists sync_batches_dispatch_lease_idx on sync_batches (status, updated_at) where status in ('dispatched', 'failed');

create index if not exists sync_errors_job_id_idx on sync_errors (job_id);

create index if not exists sync_errors_batch_id_idx on sync_errors (batch_id);

create index if not exists sync_backlog_status_idx on sync_backlog (status, updated_at);

create index if not exists wireless_authorized_networks_enabled_idx on wireless_authorized_networks (enabled, location_id);

create unique index if not exists wireless_authorized_networks_match_idx on wireless_authorized_networks (coalesce(lower(ssid), ''), coalesce(lower(bssid), ''), coalesce(location_id, ''));

create index if not exists wireless_clients_client_mac_idx on wireless_clients (client_mac);

create index if not exists wireless_clients_last_seen_idx on wireless_clients (last_seen desc);

create index if not exists wireless_clients_known_bssid_idx on wireless_clients (known_bssid) where known_bssid is not null;

create index if not exists wireless_shadow_alerts_open_idx on wireless_shadow_alerts (last_occurred_at desc) where resolved_at is null;

create index if not exists devices_wg_pubkey_idx on devices (wg_pubkey);

create index if not exists devices_username_idx on devices (username, last_seen desc);
