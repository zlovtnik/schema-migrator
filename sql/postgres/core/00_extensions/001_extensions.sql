-- object: extensions
-- folder: extensions
-- depends_on: -
-- =============================================================================
-- ssl-proxy canonical Postgres schema
-- Fresh baseline. Do not append migration-style ALTER blocks here.
-- =============================================================================

create extension if not exists pg_trgm;

create extension if not exists vector;

create extension if not exists pgcrypto;

do $$
begin
  begin
    execute 'create extension if not exists pg_stat_statements';
  exception when others then
    raise notice 'pg_stat_statements extension unavailable; query statistics will not be exported: %', sqlerrm;
  end;
end $$;

do $$
begin
  begin
    execute 'create extension if not exists pg_cron';
  exception when others then
    raise notice 'pg_cron extension unavailable; cron jobs will not be installed: %', sqlerrm;
  end;
end $$;
