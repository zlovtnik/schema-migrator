-- object: vec_dns_resolver_ledger
-- folder: tables
-- depends_on: vec_dns_policy
create table if not exists vec_dns_resolver_ledger (
  ledger_id bigserial primary key,
  wg_pubkey text not null,
  observed_at timestamptz not null,
  protocol text not null,
  query_name text,
  query_name_hash text,
  status text not null default 'observed',
  constraint vec_dns_resolver_ledger_protocol_chk check (protocol in ('doh', 'dot', 'wireguard_dns', 'dnscrypt', 'unknown')),
  constraint vec_dns_resolver_ledger_query_chk check (query_name is not null or query_name_hash is not null)
);
