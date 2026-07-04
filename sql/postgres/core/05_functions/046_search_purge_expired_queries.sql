-- object: search_purge_expired_queries
-- folder: functions
-- depends_on: search_queries
-- Deletes expired search analytics rows; search_feedback rows cascade by query_id.
create or replace function search_purge_expired_queries(p_now timestamptz default now())
returns bigint
language plpgsql
as $$
declare
  v_deleted bigint;
begin
  delete from search_queries
   where expires_at < p_now;

  get diagnostics v_deleted = row_count;
  return v_deleted;
end;
$$;
