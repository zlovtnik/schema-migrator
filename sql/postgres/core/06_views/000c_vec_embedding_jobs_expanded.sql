-- object: vec_embedding_jobs_expanded
-- folder: views
-- depends_on: vec_embedding_jobs, vec_embedding_job_leases
create or replace view vec_embedding_jobs_expanded as
select
  job.job_id,
  job.source_table,
  job.source_key,
  job.embedding_model,
  job.embedding_kind,
  job.status,
  job.priority,
  lease.attempts,
  lease.max_attempts,
  lease.lease_token,
  lease.leased_at,
  lease.locked_by,
  lease.due_at,
  job.content_sha256,
  lease.last_error,
  lease.completed_at,
  job.created_at,
  job.updated_at
from vec_embedding_jobs job
join vec_embedding_job_leases lease using (job_id);
