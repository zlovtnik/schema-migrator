-- object: vec_prune_retention
-- folder: functions
-- depends_on: vec_embeddings, vec_embedding_jobs, vec_similarity_pairs
create or replace function vec_prune_retention(
  p_event_embedding_days integer default 14,
  p_rollup_embedding_days integer default 30,
  p_similarity_pair_days integer default 14,
  p_completed_job_days integer default 7,
  p_failed_job_days integer default 30,
  p_limit integer default 5000
)
returns jsonb
language plpgsql
as $$
declare
  v_pairs_deleted integer := 0;
  v_event_embeddings_deleted integer := 0;
  v_rollup_embeddings_deleted integer := 0;
  v_completed_jobs_deleted integer := 0;
  v_failed_jobs_deleted integer := 0;
  v_behaviour_deleted integer := 0;
  v_sequences_deleted integer := 0;
  v_timing_deleted integer := 0;
begin
  with doomed as (
    select pair_id
    from vec_similarity_pairs
    where computed_at < now() - make_interval(days => greatest(coalesce(p_similarity_pair_days, 14), 1))
    order by computed_at asc, pair_id asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  deleted as (
    delete from vec_similarity_pairs pair
    using doomed
    where pair.pair_id = doomed.pair_id
    returning 1
  )
  select count(*) into v_pairs_deleted from deleted;

  with doomed as (
    select embedding_id
    from vec_embeddings
    where embedding_kind = 'event'
      and coalesce(source_observed_at, embedded_at) < now() - make_interval(days => greatest(coalesce(p_event_embedding_days, 14), 1))
    order by coalesce(source_observed_at, embedded_at) asc, embedding_id asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  deleted as (
    delete from vec_embeddings embedding
    using doomed
    where embedding.embedding_id = doomed.embedding_id
    returning 1
  )
  select count(*) into v_event_embeddings_deleted from deleted;

  with doomed as (
    select embedding_id
    from vec_embeddings
    where embedding_kind in ('behaviour_window', 'baseline_profile', 'frame_sequence', 'infrastructure_subgraph', 'timing_profile')
      and coalesce(source_observed_at, embedded_at) < now() - make_interval(days => greatest(coalesce(p_rollup_embedding_days, 30), 1))
    order by coalesce(source_observed_at, embedded_at) asc, embedding_id asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  deleted as (
    delete from vec_embeddings embedding
    using doomed
    where embedding.embedding_id = doomed.embedding_id
    returning 1
  )
  select count(*) into v_rollup_embeddings_deleted from deleted;

  with doomed as (
    select job_id
    from vec_embedding_jobs
    where status = 'completed'
      and coalesce(completed_at, updated_at) < now() - make_interval(days => greatest(coalesce(p_completed_job_days, 7), 1))
    order by coalesce(completed_at, updated_at) asc, job_id asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  deleted as (
    delete from vec_embedding_jobs job
    using doomed
    where job.job_id = doomed.job_id
    returning 1
  )
  select count(*) into v_completed_jobs_deleted from deleted;

  with doomed as (
    select job_id
    from vec_embedding_jobs
    where status = 'failed'
      and updated_at < now() - make_interval(days => greatest(coalesce(p_failed_job_days, 30), 1))
    order by updated_at asc, job_id asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  deleted as (
    delete from vec_embedding_jobs job
    using doomed
    where job.job_id = doomed.job_id
    returning 1
  )
  select count(*) into v_failed_jobs_deleted from deleted;

  with doomed as (
    select snapshot_id
    from vec_behaviour_snapshots
    where window_end < now() - make_interval(days => greatest(coalesce(p_rollup_embedding_days, 30), 1))
    order by window_end asc, snapshot_id asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  deleted as (
    delete from vec_behaviour_snapshots snapshot
    using doomed
    where snapshot.snapshot_id = doomed.snapshot_id
    returning 1
  )
  select count(*) into v_behaviour_deleted from deleted;

  with doomed as (
    select session_key
    from vec_frame_sequences
    where window_end < now() - make_interval(days => greatest(coalesce(p_rollup_embedding_days, 30), 1))
    order by window_end asc, session_key asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  deleted as (
    delete from vec_frame_sequences sequence
    using doomed
    where sequence.session_key = doomed.session_key
    returning 1
  )
  select count(*) into v_sequences_deleted from deleted;

  with doomed as (
    select profile_id
    from vec_timing_profiles
    where window_end < now() - make_interval(days => greatest(coalesce(p_rollup_embedding_days, 30), 1))
    order by window_end asc, profile_id asc
    limit greatest(coalesce(p_limit, 5000), 1)
  ),
  deleted as (
    delete from vec_timing_profiles profile
    using doomed
    where profile.profile_id = doomed.profile_id
    returning 1
  )
  select count(*) into v_timing_deleted from deleted;

  return jsonb_build_object(
    'similarity_pairs_deleted', v_pairs_deleted,
    'event_embeddings_deleted', v_event_embeddings_deleted,
    'rollup_embeddings_deleted', v_rollup_embeddings_deleted,
    'completed_jobs_deleted', v_completed_jobs_deleted,
    'failed_jobs_deleted', v_failed_jobs_deleted,
    'behaviour_snapshots_deleted', v_behaviour_deleted,
    'frame_sequences_deleted', v_sequences_deleted,
    'timing_profiles_deleted', v_timing_deleted
  );
end;
$$;

