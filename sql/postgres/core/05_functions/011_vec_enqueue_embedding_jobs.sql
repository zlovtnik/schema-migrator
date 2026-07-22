-- object: vec_enqueue_embedding_jobs
-- folder: functions
-- depends_on: vec_embedding_jobs
drop function if exists vec_enqueue_embedding_jobs(text);

create or replace function vec_enqueue_embedding_jobs(
  p_model text default 'nomic-embed-text-v2-moe',
  p_event_embedding_scope text default 'high_signal'
)
returns integer
language plpgsql
as $$
declare
  v_count integer := 0;
  v_event_count integer := 0;
  v_event_cursor timestamptz;
  v_event_cursor_next timestamptz;
begin
  if not vec_try_begin_job('vec_enqueue_embedding_jobs') then
    return 0;
  end if;

  select coalesce(
    (select cursor_value::timestamptz
       from sync_cursors
      where stream_name = 'vec_embeddings.sync_events.wireless.audit'),
    timestamptz '1970-01-01 00:00:00+00'
  )
  into v_event_cursor;

  with cursor_event_keys as (
    select
      e.dedupe_key,
      greatest(e.updated_at, coalesce(frame.updated_at, e.updated_at)) as event_updated_at,
      greatest(e.updated_at, coalesce(frame.updated_at, e.updated_at)) as cursor_updated_at
    from sync_events e
    left join wireless_frames_expanded frame on frame.dedupe_key = e.dedupe_key
    where e.stream_name = 'wireless.audit'
      and e.status = 'batched'
      and greatest(e.updated_at, coalesce(frame.updated_at, e.updated_at)) > v_event_cursor
    order by cursor_updated_at, e.dedupe_key
  ),
  alert_event_keys as (
    select
      e.dedupe_key,
      greatest(e.updated_at, coalesce(frame.updated_at, e.updated_at), alert.created_at) as event_updated_at,
      alert.created_at as cursor_updated_at
    from vec_alerts alert
    join sync_events e
      on e.stream_name = 'wireless.audit'
     and e.status = 'batched'
     and alert.created_at > v_event_cursor
     and (
       alert.metadata->>'dedupe_key' = e.dedupe_key
       or alert.metadata->>'source_key' = e.dedupe_key
     )
    left join wireless_frames_expanded frame on frame.dedupe_key = e.dedupe_key
    order by cursor_updated_at, e.dedupe_key
  ),
  event_keys as (
    select * from cursor_event_keys
    union
    select * from alert_event_keys
  ),
  event_jobs as (
    select
      'sync_events'::text as source_table,
      source.dedupe_key::text as source_key,
      p_model as embedding_model,
      'event'::text as embedding_kind,
      10 as priority
    from event_keys keys
    join sync_events_expanded source
      on source.dedupe_key = keys.dedupe_key
    left join vec_embeddings_expanded existing
      on existing.source_table = 'sync_events'
     and existing.source_key = source.dedupe_key
     and existing.embedding_model = p_model
     and existing.embedding_kind = 'event'
    left join vec_embedding_jobs_expanded existing_job
      on existing_job.source_table = 'sync_events'
     and existing_job.source_key = source.dedupe_key
     and existing_job.embedding_model = p_model
     and existing_job.embedding_kind = 'event'
    where source.stream_name = 'wireless.audit'
      and source.status = 'batched'
      and (
        coalesce(nullif(p_event_embedding_scope, ''), 'high_signal') = 'all'
        or coalesce(source.handshake_captured, false)
        or coalesce(coordinator.safe_double(source.payload->>'risk_score'), 0::double precision) >= 0.5
        or coordinator.has_threat_tag(coordinator.safe_jsonb_array(source.payload->'tags'))
        or exists (
          select 1
          from vec_alerts alert
          where alert.created_at >= source.observed_at - interval '1 hour'
            and alert.created_at <= source.observed_at + interval '24 hours'
            and (
              alert.metadata->>'dedupe_key' = source.dedupe_key
              or alert.metadata->>'source_key' = source.dedupe_key
              or (
                source.source_mac is not null
                and lower(alert.source_mac) = lower(source.source_mac)
              )
              or (
                source.bssid is not null
                and lower(alert.metadata->>'bssid') = lower(source.bssid)
              )
              or (
                source.destination_bssid is not null
                and lower(alert.metadata->>'destination_bssid') = lower(source.destination_bssid)
              )
            )
        )
      )
      and (
        existing.embedding_id is null
        or (
          existing_job.job_id is null
          and keys.event_updated_at > existing.embedded_at
        )
        or (
          existing_job.status = 'completed'
          and keys.event_updated_at > coalesce(existing_job.completed_at, existing.embedded_at)
        )
      )
  ),
  device_jobs as (
    select
      'devices'::text as source_table,
      mac_id::text as source_key,
      p_model as embedding_model,
      'device'::text as embedding_kind,
      30 as priority
    from devices source
    left join vec_embeddings_expanded existing
      on existing.source_table = 'devices'
     and existing.source_key = source.mac_id
     and existing.embedding_model = p_model
     and existing.embedding_kind = 'device'
    left join vec_embedding_jobs_expanded existing_job
      on existing_job.source_table = 'devices'
     and existing_job.source_key = source.mac_id
     and existing_job.embedding_model = p_model
     and existing_job.embedding_kind = 'device'
    where existing.embedding_id is null
       or (
         existing_job.job_id is null
         and source.last_seen > existing.embedded_at
       )
       or (
         existing_job.status = 'completed'
         and source.last_seen > coalesce(existing_job.completed_at, existing.embedded_at)
       )
  ),
  behaviour_jobs as (
    select
      'vec_behaviour_snapshots'::text as source_table,
      snapshot_id::text as source_key,
      p_model as embedding_model,
      'behaviour_window'::text as embedding_kind,
      20 as priority
    from vec_behaviour_snapshots_expanded source
    left join vec_embeddings_expanded existing
      on existing.source_table = 'vec_behaviour_snapshots'
     and existing.source_key = source.snapshot_id::text
     and existing.embedding_model = p_model
     and existing.embedding_kind = 'behaviour_window'
    left join vec_embedding_jobs_expanded existing_job
      on existing_job.source_table = 'vec_behaviour_snapshots'
     and existing_job.source_key = source.snapshot_id::text
     and existing_job.embedding_model = p_model
     and existing_job.embedding_kind = 'behaviour_window'
    where existing.embedding_id is null
       or (
         existing_job.job_id is null
         and source.updated_at > existing.embedded_at
       )
       or (
         existing_job.status = 'completed'
         and source.updated_at > coalesce(existing_job.completed_at, existing.embedded_at)
       )
  ),
  frame_sequence_jobs as (
    select
      'vec_frame_sequences'::text as source_table,
      fs.session_key::text as source_key,
      p_model as embedding_model,
      'frame_sequence'::text as embedding_kind,
      18 as priority
    from vec_frame_sequences fs
    left join vec_embeddings_expanded existing
      on existing.source_table = 'vec_frame_sequences'
     and existing.source_key = fs.session_key
     and existing.embedding_model = p_model
     and existing.embedding_kind = 'frame_sequence'
    left join vec_embedding_jobs_expanded existing_job
      on existing_job.source_table = 'vec_frame_sequences'
     and existing_job.source_key = fs.session_key
     and existing_job.embedding_model = p_model
     and existing_job.embedding_kind = 'frame_sequence'
    where existing.embedding_id is null
       or (
         existing_job.job_id is null
         and fs.updated_at > existing.embedded_at
       )
       or (
         existing_job.status = 'completed'
         and fs.updated_at > coalesce(existing_job.completed_at, existing.embedded_at)
       )
  ),
  timing_profile_jobs as (
    select
      'vec_timing_profiles'::text as source_table,
      tp.profile_id::text as source_key,
      p_model as embedding_model,
      'timing_profile'::text as embedding_kind,
      17 as priority
    from vec_timing_profiles_expanded tp
    left join vec_embeddings_expanded existing
      on existing.source_table = 'vec_timing_profiles'
     and existing.source_key = tp.profile_id::text
     and existing.embedding_model = p_model
     and existing.embedding_kind = 'timing_profile'
    left join vec_embedding_jobs_expanded existing_job
      on existing_job.source_table = 'vec_timing_profiles'
     and existing_job.source_key = tp.profile_id::text
     and existing_job.embedding_model = p_model
     and existing_job.embedding_kind = 'timing_profile'
    where existing.embedding_id is null
       or (
         existing_job.job_id is null
         and tp.updated_at > existing.embedded_at
       )
       or (
         existing_job.status = 'completed'
         and tp.updated_at > coalesce(existing_job.completed_at, existing.embedded_at)
       )
  ),
  graph_keys as (
    select source_key, max(updated_at) as source_updated_at
    from (
      select node_a as source_key, updated_at
      from vec_infrastructure_graph
      where node_a_type = 'bssid'
      union all
      select node_b as source_key, updated_at
      from vec_infrastructure_graph
      where node_b_type = 'bssid'
    ) keys
    group by source_key
  ),
  graph_jobs as (
    select
      'vec_infrastructure_graph'::text as source_table,
      keys.source_key,
      p_model as embedding_model,
      'infrastructure_subgraph'::text as embedding_kind,
      15 as priority
    from graph_keys keys
    left join vec_embeddings_expanded existing
      on existing.source_table = 'vec_infrastructure_graph'
     and existing.source_key = keys.source_key
     and existing.embedding_model = p_model
     and existing.embedding_kind = 'infrastructure_subgraph'
    left join vec_embedding_jobs_expanded existing_job
      on existing_job.source_table = 'vec_infrastructure_graph'
     and existing_job.source_key = keys.source_key
     and existing_job.embedding_model = p_model
     and existing_job.embedding_kind = 'infrastructure_subgraph'
    where existing.embedding_id is null
       or (
         existing_job.job_id is null
         and keys.source_updated_at > existing.embedded_at
       )
       or (
         existing_job.status = 'completed'
         and keys.source_updated_at > coalesce(existing_job.completed_at, existing.embedded_at)
       )
  ),
  baseline_jobs as (
    select
      'vec_baseline_profiles'::text as source_table,
      bp.bssid as source_key,
      p_model as embedding_model,
      'baseline_profile'::text as embedding_kind,
      25 as priority
    from vec_baseline_profiles bp
    left join lateral (
      select count(*) as new_frame_count
      from (
        select source.dedupe_key
        from wireless_frames_expanded source
        join sync_events event on event.dedupe_key = source.dedupe_key
        where source.bssid is not null
          and lower(source.bssid) = bp.bssid
          and source.updated_at > bp.updated_at
          and event.status = 'batched'
        union
        select source.dedupe_key
        from wireless_frames_expanded source
        join sync_events event on event.dedupe_key = source.dedupe_key
        where source.destination_bssid is not null
          and lower(source.destination_bssid) = bp.bssid
          and source.updated_at > bp.updated_at
          and event.status = 'batched'
      ) source
    ) frames on true
    left join vec_embeddings_expanded existing
      on existing.source_table = 'vec_baseline_profiles'
     and existing.source_key = bp.bssid
     and existing.embedding_model = p_model
     and existing.embedding_kind = 'baseline_profile'
    left join vec_embedding_jobs_expanded existing_job
      on existing_job.source_table = 'vec_baseline_profiles'
     and existing_job.source_key = bp.bssid
     and existing_job.embedding_model = p_model
     and existing_job.embedding_kind = 'baseline_profile'
    where frames.new_frame_count >= 50
      and (
        existing.embedding_id is null
        or (
          existing_job.job_id is null
          and bp.updated_at > existing.embedded_at
        )
        or (
          existing_job.status = 'completed'
          and bp.updated_at > coalesce(existing_job.completed_at, existing.embedded_at)
        )
      )
  ),
  inserted as (
    insert into vec_embedding_jobs (
      source_table, source_key, embedding_model, embedding_kind,
      priority, status, created_at, updated_at
    )
    select
      source_table,
      source_key,
      embedding_model,
      embedding_kind,
      min(priority) as priority,
      'pending',
      now(),
      now()
    from (
      select * from event_jobs
      union all
      select * from device_jobs
      union all
      select * from behaviour_jobs
      union all
      select * from frame_sequence_jobs
      union all
      select * from timing_profile_jobs
      union all
      select * from baseline_jobs
      union all
      select * from graph_jobs
    ) jobs
    group by source_table, source_key, embedding_model, embedding_kind
    on conflict (source_table, source_key, embedding_model, embedding_kind) do update set
      status = 'pending',
      priority = least(vec_embedding_jobs.priority, excluded.priority),
      content_sha256 = null,
      updated_at = now()
    where vec_embedding_jobs.status = 'completed'
    returning job_id, source_table, source_key, embedding_kind
  ),
  leases_reset as (
    update vec_embedding_job_leases lease
       set due_at = least(lease.due_at, now()),
           completed_at = null,
           attempts = 0,
           lease_token = null,
           leased_at = null,
           locked_by = null,
           last_error = null
      from inserted
     where lease.job_id = inserted.job_id
    returning lease.job_id
  )
  select
    (select count(*) from inserted),
    (select count(*) from event_keys),
    (select max(cursor_updated_at) from event_keys)
    into v_count, v_event_count, v_event_cursor_next
  ;

  if v_event_count > 0 and v_event_cursor_next is not null then
    insert into sync_cursors (stream_name, cursor_value, updated_at)
    values (
      'vec_embeddings.sync_events.wireless.audit',
      v_event_cursor_next::text,
      now()
    )
    on conflict (stream_name) do update set
      cursor_value = greatest(sync_cursors.cursor_value::timestamptz, excluded.cursor_value::timestamptz)::text,
      updated_at = now();
  end if;

  perform vec_finish_job('vec_enqueue_embedding_jobs');
  return v_count;
exception when others then
  perform vec_finish_job('vec_enqueue_embedding_jobs');
  raise;
end;
$$;
