-- object: device_graph_workmap_indexes
-- folder: indexes
-- depends_on: sync_events, sync_backlog, wireless_frames, vec_dns_resolver_ledger, vec_infrastructure_graph, search_feedback

create index if not exists sync_events_stream_status_idx
  on sync_events (stream_name, status)
  where status in ('pending', 'processing');

create index if not exists sync_backlog_retry_ready_idx
  on sync_backlog (status, updated_at)
  where status = 'pending'
    and attempt_count < max_attempts;

create index if not exists wireless_frames_source_mac_time_idx
  on wireless_frames (lower(source_mac), created_at desc)
  where source_mac is not null;

create index if not exists vec_dns_resolver_ledger_expires_idx
  on vec_dns_resolver_ledger (expires_at);

create index if not exists vec_infrastructure_graph_last_seen_idx
  on vec_infrastructure_graph (last_seen);

create index if not exists vec_infrastructure_graph_node_a_lookup_idx
  on vec_infrastructure_graph (node_a, node_a_type, last_seen desc);

create index if not exists vec_infrastructure_graph_node_b_lookup_idx
  on vec_infrastructure_graph (node_b, node_b_type, last_seen desc);

create index if not exists search_feedback_created_idx
  on search_feedback (created_at desc);
