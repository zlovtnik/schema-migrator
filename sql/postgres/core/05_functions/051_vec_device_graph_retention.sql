-- object: vec_device_graph_retention
-- folder: functions
-- depends_on: vec_dns_resolver_ledger, vec_infrastructure_graph

create or replace function vec_prune_device_graph_retention(
  p_dns_ledger_days integer default 90,
  p_infrastructure_edge_days integer default 90,
  p_limit integer default 5000
)
returns jsonb
language plpgsql
as $$
declare
  v_dns_deleted integer := 0;
  v_edges_deleted integer := 0;
begin
  with doomed as (
    select ledger_id
    from vec_dns_resolver_ledger
    where expires_at < now()
       or observed_at < now() - make_interval(days => greatest(coalesce(p_dns_ledger_days, 90), 1))
    order by observed_at asc, ledger_id asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  deleted as (
    delete from vec_dns_resolver_ledger ledger
    using doomed
    where ledger.ledger_id = doomed.ledger_id
    returning 1
  )
  select count(*) into v_dns_deleted from deleted;

  with doomed as (
    select edge_id
    from vec_infrastructure_graph
    where last_seen < now() - make_interval(days => greatest(coalesce(p_infrastructure_edge_days, 90), 1))
    order by last_seen asc, edge_id asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  deleted as (
    delete from vec_infrastructure_graph graph
    using doomed
    where graph.edge_id = doomed.edge_id
    returning 1
  )
  select count(*) into v_edges_deleted from deleted;

  return jsonb_build_object(
    'dns_ledger_deleted', v_dns_deleted,
    'infrastructure_edges_deleted', v_edges_deleted
  );
end;
$$;
