-- object: coordinator.ensure_cursor
-- folder: functions
-- depends_on: sync_cursors
create or replace function coordinator.ensure_cursor(
  p_stream_name text,
  p_default_cursor text default '0'
)
returns text
language plpgsql
as $$
declare
  v_cursor text;
begin
  insert into sync_cursors (stream_name, cursor_value, updated_at)
  values (p_stream_name, p_default_cursor, now())
  on conflict (stream_name) do nothing;

  select cursor_value
    into v_cursor
    from sync_cursors
   where stream_name = p_stream_name;

  return v_cursor;
end;
$$;
