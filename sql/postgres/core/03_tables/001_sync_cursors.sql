-- object: sync_cursors
-- folder: tables
-- depends_on: coordinator
create table if not exists sync_cursors (
  stream_name text primary key,
  cursor_value text not null,
  updated_at timestamptz not null default now()
);
