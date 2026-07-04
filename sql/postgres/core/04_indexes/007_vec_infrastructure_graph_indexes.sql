-- object: vec_infrastructure_graph indexes
-- folder: indexes
-- depends_on: vec_infrastructure_graph
create index if not exists vec_infrastructure_graph_node_a_idx
  on vec_infrastructure_graph (node_a_type, node_a, edge_type, last_seen desc);

create index if not exists vec_infrastructure_graph_node_b_idx
  on vec_infrastructure_graph (node_b_type, node_b, edge_type, last_seen desc);
