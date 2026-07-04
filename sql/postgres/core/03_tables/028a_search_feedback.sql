-- object: search_feedback
-- folder: tables
-- depends_on: search_queries
create table if not exists search_feedback (
  feedback_id bigserial primary key,
  query_id    bigint not null references search_queries(query_id) on delete cascade,
  source_key  text not null,
  relevant    boolean not null,
  created_at  timestamptz not null default now()
);

-- Repeated feedback events are allowed; consumers can aggregate by query/source.
create index if not exists search_feedback_query_idx
  on search_feedback (query_id, source_key);
