-- object: vec_update_transition_model
-- folder: functions
-- depends_on: vec_transition_model
-- Update transition counts from ordered frame_subtype sequences in sync_events
-- over a rolling 24-hour window. Uses Laplace-smoothed bigrams.
create or replace function vec_update_transition_model()
returns integer
language plpgsql
as $$
declare
  v_count integer := 0;
begin
  if not vec_try_begin_job('vec_update_transition_model') then
    return 0;
  end if;

  with windowed as (
    select
      coalesce(
        nullif(frame_subtype, ''),
        nullif(payload->>'frame_subtype', '')
      ) as frame_subtype,
      coalesce(session_key, payload->>'session_key') as session_key,
      observed_at
    from sync_events_expanded
    where stream_name = 'wireless.audit'
      and status = 'batched'
      and observed_at >= now() - interval '24 hours'
      and coalesce(session_key, payload->>'session_key') is not null
      and coalesce(
        nullif(frame_subtype, ''),
        nullif(payload->>'frame_subtype', '')
      ) is not null
  ),
  ordered as (
    select
      session_key,
      frame_subtype,
      lag(frame_subtype) over (partition by session_key order by observed_at) as prev_subtype
    from windowed
  ),
  bigrams as (
    select upper(regexp_replace(prev_subtype, '-', '_', 'g'))::text as prev_token,
           upper(regexp_replace(frame_subtype, '-', '_', 'g'))::text as next_token
    from ordered
    where prev_subtype is not null
  )
  insert into vec_transition_model (prev_token, next_token, embedding_kind, count, last_updated)
  select prev_token, next_token, 'frame_sequence', count(*)::bigint, now()
  from bigrams
  group by prev_token, next_token
  on conflict (prev_token, next_token, embedding_kind) do update set
    count = vec_transition_model.count + excluded.count,
    last_updated = now();

  get diagnostics v_count = row_count;
  perform vec_finish_job('vec_update_transition_model');
  return v_count;
exception when others then
  perform vec_finish_job('vec_update_transition_model');
  raise;
end;
$$;
