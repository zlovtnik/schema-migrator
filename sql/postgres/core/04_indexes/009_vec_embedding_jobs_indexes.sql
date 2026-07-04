-- object: vec_embedding_jobs indexes
-- folder: indexes
-- depends_on: vec_embedding_jobs
create index if not exists vec_embedding_jobs_pending_idx
  on vec_embedding_jobs (priority, due_at, job_id)
  where status in ('pending', 'failed')
    and attempts < max_attempts;

create index if not exists vec_embedding_jobs_pending_kind_idx
  on vec_embedding_jobs (embedding_kind, priority, due_at, job_id)
  where status in ('pending', 'failed')
    and attempts < max_attempts;

create index if not exists vec_embedding_jobs_lease_idx
  on vec_embedding_jobs (leased_at, priority, job_id)
  where status = 'leased'
    and attempts < max_attempts;

create index if not exists vec_embedding_jobs_lease_kind_idx
  on vec_embedding_jobs (embedding_kind, leased_at, priority, job_id)
  where status = 'leased'
    and attempts < max_attempts;

create index if not exists vec_embedding_jobs_completion_idx
  on vec_embedding_jobs (job_id, lease_token)
  where status in ('pending', 'leased', 'failed');
