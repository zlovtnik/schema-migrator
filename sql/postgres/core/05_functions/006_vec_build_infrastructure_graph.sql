-- object: vec_build_infrastructure_graph
-- folder: functions
-- depends_on: vec_infrastructure_graph, wireless_frames
create or replace function vec_build_infrastructure_graph(
  p_from timestamptz default now() - interval '1 hour',
  p_to timestamptz default now()
)
returns integer
language plpgsql
as $$
declare
  v_count integer := 0;
  v_row_count integer := 0;
begin
  if not vec_try_begin_job('vec_build_infrastructure_graph') then
    return 0;
  end if;

  with base as (
    select
      lower(nullif(coalesce(bssid, payload->>'bssid', destination_bssid, payload->>'destination_bssid'), '')) as bssid,
      lower(nullif(coalesce(source_mac, payload->>'source_mac'), '')) as source_mac,
      lower(nullif(coalesce(ssid, payload->>'ssid'), '')) as ssid,
      lower(regexp_replace(coalesce(bssid, payload->>'bssid', destination_bssid, payload->>'destination_bssid'), '[:\-]', '', 'g')) as normalized_bssid,
      observed_at,
      channel_number,
      stream_name,
      sensor_id,
      location_id
    from sync_events_expanded
    where stream_name = 'wireless.audit'
      and status = 'batched'
      and observed_at >= p_from
      and observed_at < p_to
      and nullif(coalesce(bssid, payload->>'bssid', destination_bssid, payload->>'destination_bssid'), '') is not null
  ),
  association_edges as (
    select
      bssid as node_a,
      'bssid'::text as node_a_type,
      source_mac as node_b,
      'client_mac'::text as node_b_type,
      'association'::text as edge_type,
      count(*)::numeric as weight,
      max(observed_at) as last_seen
    from base
    where source_mac is not null
    group by bssid, source_mac
  ),
  probe_edges as (
    select
      source_mac as node_a,
      'client_mac'::text as node_a_type,
      ssid as node_b,
      'ssid'::text as node_b_type,
      'probe_target'::text as edge_type,
      count(*)::numeric as weight,
      max(observed_at) as last_seen
    from base
    where ssid is not null
      and source_mac is not null
    group by source_mac, ssid
  ),
  roaming_edges as (
    select
      source_mac as node_a,
      'client_mac'::text as node_a_type,
      bssid as node_b,
      'bssid'::text as node_b_type,
      'roaming'::text as edge_type,
      count(*)::numeric as weight,
      max(observed_at) as last_seen
    from (
      select distinct source_mac, bssid, observed_at
      from base
      where source_mac is not null
        and bssid is not null
    ) sub
    group by source_mac, bssid
  ),
  vendor_edges as (
    select
      bssid as node_a,
      'bssid'::text as node_a_type,
      substr(normalized_bssid, 1, 6) as node_b,
      'vendor'::text as node_b_type,
      'vendor_link'::text as edge_type,
      count(*)::numeric as weight,
      max(observed_at) as last_seen
    from base
    where normalized_bssid is not null
    group by bssid, substr(normalized_bssid, 1, 6)
  ),
  same_channel_edges as (
    select
      b1.bssid as node_a,
      'bssid'::text as node_a_type,
      b2.bssid as node_b,
      'bssid'::text as node_b_type,
      'same_channel'::text as edge_type,
      count(*)::numeric as weight,
      max(greatest(b1.observed_at, b2.observed_at)) as last_seen
    from base b1
    join base b2
      on b1.sensor_id is not distinct from b2.sensor_id
     and b1.channel_number is not distinct from b2.channel_number
     and b1.bssid < b2.bssid
     and abs(extract(epoch from b1.observed_at - b2.observed_at)) <= 10
    group by b1.bssid, b2.bssid
  ),
  rf_proximity_edges as (
    select
      b1.bssid as node_a,
      'bssid'::text as node_a_type,
      b2.bssid as node_b,
      'bssid'::text as node_b_type,
      'rf_proximity'::text as edge_type,
      count(*)::numeric as weight,
      max(greatest(b1.observed_at, b2.observed_at)) as last_seen
    from base b1
    join base b2
      on b1.sensor_id is not distinct from b2.sensor_id
     and b1.bssid < b2.bssid
     and abs(extract(epoch from b1.observed_at - b2.observed_at)) <= 10
    group by b1.bssid, b2.bssid
  ),
  all_edges as (
    select * from association_edges
    union all
    select * from probe_edges
    union all
    select * from roaming_edges
    union all
    select * from vendor_edges
    union all
    select * from same_channel_edges
    union all
    select * from rf_proximity_edges
  )
  insert into vec_infrastructure_graph (
    node_a, node_a_type, node_b, node_b_type, edge_type, weight, last_seen, updated_at
  )
  select
    node_a, node_a_type, node_b, node_b_type, edge_type, sum(weight), max(last_seen), now()
  from all_edges
  group by node_a, node_a_type, node_b, node_b_type, edge_type
  on conflict (node_a, node_a_type, node_b, node_b_type, edge_type) do update set
    weight = vec_infrastructure_graph.weight + excluded.weight,
    last_seen = greatest(vec_infrastructure_graph.last_seen, excluded.last_seen),
    updated_at = now();

  get diagnostics v_count = row_count;
  perform vec_finish_job('vec_build_infrastructure_graph');
  return v_count;
exception when others then
  perform vec_finish_job('vec_build_infrastructure_graph');
  raise;
end;
$$;
