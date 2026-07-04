-- object: device_graph_workmap_hardening_columns
-- folder: tables
-- depends_on: sync_backlog, wireless_clients, vec_dns_resolver_ledger, vec_alerts, vec_behaviour_snapshots, vec_timing_profiles, vec_frame_sequences, vec_baseline_profiles, search_queries

alter table sync_backlog
  add column if not exists max_attempts integer not null default 5;

alter table wireless_clients
  alter column probe_count type bigint;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'chk_sync_backlog_attempts'
      and conrelid = 'sync_backlog'::regclass
      and contype = 'c'
  ) then
    alter table sync_backlog
      add constraint chk_sync_backlog_attempts
      check (attempt_count >= 0 and max_attempts > 0);
  end if;
end;
$$;

alter table vec_dns_resolver_ledger
  add column if not exists expires_at timestamptz;

update vec_dns_resolver_ledger
   set expires_at = observed_at + interval '90 days'
 where expires_at is null;

alter table vec_dns_resolver_ledger
  alter column expires_at set default (now() + interval '90 days');

alter table vec_dns_resolver_ledger
  alter column expires_at set not null;

alter table vec_alerts
  add column if not exists resolved_at timestamptz,
  add column if not exists suppressed_until timestamptz,
  add column if not exists acknowledged_by text;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'vec_alerts_type_chk'
      and conrelid = 'vec_alerts'::regclass
      and contype = 'c'
  ) then
    alter table vec_alerts
      add constraint vec_alerts_type_chk
      check (
        alert_type in (
          'behaviour_anomaly',
          'deauth_flood',
          'deauth_precursor',
          'device_fingerprint_change',
          'dns_privacy_leak',
          'embedding_drift',
          'high_risk_ap',
          'near_duplicate_cluster',
          'new_device',
          'rf_impossible_travel',
          'rogue_cluster',
          'rogue_rf_path',
          'signal_anomaly',
          'zero_trust_overlay_risk'
        )
      )
      not valid;
  end if;
end;
$$;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'vec_behaviour_snapshots_embedding_text_chk'
      and conrelid = 'vec_behaviour_snapshots'::regclass
      and contype = 'c'
  ) then
    alter table vec_behaviour_snapshots
      add constraint vec_behaviour_snapshots_embedding_text_chk
      check (nullif(embedding_text, '') is not null)
      not valid;
  end if;
end;
$$;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'vec_timing_profiles_embedding_text_chk'
      and conrelid = 'vec_timing_profiles'::regclass
      and contype = 'c'
  ) then
    alter table vec_timing_profiles
      add constraint vec_timing_profiles_embedding_text_chk
      check (nullif(embedding_text, '') is not null)
      not valid;
  end if;
end;
$$;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'vec_frame_sequences_sequence_tokens_len_chk'
      and conrelid = 'vec_frame_sequences'::regclass
      and contype = 'c'
  ) then
    alter table vec_frame_sequences
      add constraint vec_frame_sequences_sequence_tokens_len_chk
      check (length(sequence_tokens) < 65536)
      not valid;
  end if;
end;
$$;

alter table vec_baseline_profiles
  add column if not exists baseline_window_days integer not null default 7;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'vec_baseline_profiles_window_days_chk'
      and conrelid = 'vec_baseline_profiles'::regclass
      and contype = 'c'
  ) then
    alter table vec_baseline_profiles
      add constraint vec_baseline_profiles_window_days_chk
      check (baseline_window_days > 0);
  end if;
end;
$$;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'search_queries_query_vec_dims_chk'
      and conrelid = 'search_queries'::regclass
      and contype = 'c'
  ) then
    alter table search_queries
      add constraint search_queries_query_vec_dims_chk
      check (query_vec is null or vector_dims(query_vec) = 768)
      not valid;
  end if;
end;
$$;
