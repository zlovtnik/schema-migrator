-- object: vec_infrastructure_graph
-- folder: tables
-- depends_on: wireless_frames
create table if not exists vec_infrastructure_graph (
  edge_id bigserial primary key,
  node_a text not null,
  node_a_type text not null check (node_a_type in ('bssid', 'client_mac', 'ssid', 'vendor')),
  node_b text not null,
  node_b_type text not null check (node_b_type in ('bssid', 'client_mac', 'ssid', 'vendor')),
  edge_type text not null check (edge_type in ('association', 'probe_target', 'roaming', 'rf_proximity', 'same_channel', 'vendor_link')),
  weight numeric not null default 1,
  last_seen timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vec_infrastructure_graph_unique unique (node_a, node_a_type, node_b, node_b_type, edge_type)
);
