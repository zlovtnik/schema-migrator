-- object: vec_dns_violation_summary
-- folder: materialized_views
-- depends_on: vec_dns_resolver_ledger

drop materialized view if exists vec_dns_violation_summary;

create materialized view vec_dns_violation_summary as
select
  wg_pubkey,
  date_trunc('day', observed_at)::date as observed_date,
  protocol,
  count(*) filter (where status <> 'observed')::bigint as violation_count,
  count(*)::bigint as observation_count,
  max(observed_at) as last_observed_at
from vec_dns_resolver_ledger
group by wg_pubkey, date_trunc('day', observed_at)::date, protocol;

comment on materialized view vec_dns_violation_summary is
  'Daily DNS resolver observations and violation counts by WireGuard public key and protocol.';

create unique index if not exists vec_dns_violation_summary_pk
  on vec_dns_violation_summary (wg_pubkey, observed_date, protocol);
