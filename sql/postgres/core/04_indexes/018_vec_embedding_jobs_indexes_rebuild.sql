-- object: vec_embedding_jobs indexes rebuild
-- folder: indexes
-- depends_on: vec_embedding_jobs, vec_embedding_job_leases
drop index if exists vec_embedding_jobs_pending_idx;
drop index if exists vec_embedding_jobs_pending_kind_idx;
drop index if exists vec_embedding_jobs_lease_idx;
drop index if exists vec_embedding_jobs_lease_kind_idx;
drop index if exists vec_embedding_jobs_completion_idx;

create index vec_embedding_jobs_pending_idx
  on vec_embedding_jobs (status, priority, job_id)
  where status in ('pending', 'failed');

create index vec_embedding_jobs_pending_kind_idx
  on vec_embedding_jobs (embedding_kind, status, priority, job_id)
  where status in ('pending', 'failed');

create index vec_embedding_jobs_lease_idx
  on vec_embedding_job_leases (leased_at, due_at, job_id)
  where leased_at is not null;

create index vec_embedding_jobs_lease_kind_idx
  on vec_embedding_job_leases (due_at, attempts, max_attempts, job_id);

create index vec_embedding_jobs_completion_idx
  on vec_embedding_job_leases (job_id, lease_token);
