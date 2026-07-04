-- object: coordinator.safe helpers
-- folder: functions
-- depends_on: coordinator
create or replace function coordinator.safe_int(p_value text)
returns integer
language plpgsql
immutable
as $$
begin
  if p_value is null or p_value !~ '^-?[0-9]+$' then
    return null;
  end if;

  begin
    return p_value::integer;
  exception
    when others then
      return null;
  end;
end;
$$;

create or replace function coordinator.safe_bigint(p_value text)
returns bigint
language sql
immutable
as $$
  select case when p_value ~ '^-?[0-9]+$' then p_value::bigint end
$$;

create or replace function coordinator.safe_double(p_value text)
returns double precision
language sql
immutable
as $$
  select case
    when p_value ~ '^-?([0-9]+(\.[0-9]+)?|\.[0-9]+)([eE][+-]?[0-9]+)?$'
    then p_value::double precision
  end
$$;

create or replace function coordinator.safe_bool(p_value text)
returns boolean
language sql
immutable
as $$
  select case
    when lower(p_value) in ('true', 't', '1', 'yes', 'y') then true
    when lower(p_value) in ('false', 'f', '0', 'no', 'n') then false
  end
$$;

create or replace function coordinator.safe_timestamptz(p_value text)
returns timestamptz
language plpgsql
stable
as $$
begin
  if p_value is null or btrim(p_value) = '' then
    return null;
  end if;

  begin
    return p_value::timestamptz;
  exception
    when others then
      return null;
  end;
end;
$$;

create or replace function coordinator.safe_jsonb_array(p_value jsonb)
returns jsonb
language sql
immutable
as $$
  select case when jsonb_typeof(p_value) = 'array' then p_value else '[]'::jsonb end
$$;

create or replace function coordinator.has_threat_tag(p_tags jsonb)
returns boolean
language sql
immutable
as $$
  select exists (
    select 1
    from jsonb_array_elements_text(coordinator.safe_jsonb_array(p_tags)) as tag(value)
    where tag.value like 'threat:%'
  )
$$;
