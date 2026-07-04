-- object: search_queries
-- folder: tables
-- depends_on: extensions
-- Search analytics store hashes by default; raw query_text/result_keys are
-- nullable and reserved for explicit diagnostic opt-in paths.
create table if not exists search_queries (
  query_id          bigserial primary key,
  query_text        text,
  hashed_query_text text not null,
  query_kind        text not null,
  query_vec         vector,
  top_k             integer not null default 10,
  result_keys       text[],
  result_key_hashes text[] not null default '{}',
  session_hash      text,
  latency_ms        integer,
  created_at        timestamptz not null default now(),
  expires_at        timestamptz not null default (now() + interval '30 days'),
  constraint search_queries_top_k_chk check (top_k > 0),
  constraint search_queries_latency_chk check (latency_ms is null or latency_ms >= 0)
);

create index if not exists search_queries_created_idx
  on search_queries (created_at desc);

create index if not exists search_queries_expires_idx
  on search_queries (expires_at);

create index if not exists search_queries_hash_idx
  on search_queries (hashed_query_text);
