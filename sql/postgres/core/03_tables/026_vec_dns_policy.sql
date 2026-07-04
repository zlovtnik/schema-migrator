-- object: vec_dns_policy
-- folder: tables
-- depends_on: devices
create table if not exists vec_dns_policy (
  wg_pubkey text primary key,
  policy text not null,
  allow_mdns boolean not null default false,
  updated_at timestamptz not null default now(),
  constraint vec_dns_policy_policy_chk check (policy in ('secure_required', 'monitor', 'disabled'))
);
