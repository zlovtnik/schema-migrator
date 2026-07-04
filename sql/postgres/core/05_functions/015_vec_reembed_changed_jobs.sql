-- object: vec_reembed_changed_jobs
-- folder: functions
-- depends_on: vec_embeddings, vec_embedding_jobs
-- V021: Add vec_reembed_changed_jobs() function for re-queuing
--
-- After the text builder changes in Phase 3 (noise filtering, ssid reordering,
-- WPS name normalization), existing embeddings may have different content_sha256
-- values. This function identifies rows whose content_text digest no longer
-- matches the stored content_sha256 and re-queues them as pending embedding jobs.
--
-- Usage:
--   SELECT vec_reembed_changed_jobs(p_limit => 1000);
--   -- Returns number of jobs re-queued

CREATE OR REPLACE FUNCTION vec_reembed_changed_jobs(
    p_limit INTEGER DEFAULT 1000
)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_count INTEGER := 0;
    v_job_id BIGINT;
BEGIN
    -- Insert new embedding jobs for rows where content_text would produce
    -- a different digest than the stored content_sha256.
    --
    -- We use sha256(content_text) as the expected digest. If it doesn't match
    -- the stored content_sha256, the text builder would produce different text
    -- and therefore a different embedding.
    INSERT INTO vec_embedding_jobs (
        source_table,
        source_key,
        embedding_model,
        embedding_kind,
        status,
        priority,
        max_attempts,
        due_at,
        created_at,
        updated_at
    )
    SELECT
        e.source_table,
        e.source_key,
        e.embedding_model,
        e.embedding_kind,
        'pending',
        0,   -- normal priority for re-embed
        3,   -- max 3 attempts
        NOW(),
        NOW(),
        NOW()
    FROM vec_embeddings e
    WHERE e.content_sha256 IS DISTINCT FROM encode(
        digest(e.content_text, 'sha256'), 'hex'
    )
    AND NOT EXISTS (
        -- Avoid re-queuing jobs that are already pending/leased
        SELECT 1 FROM vec_embedding_jobs j
        WHERE j.source_table = e.source_table
          AND j.source_key = e.source_key
          AND j.embedding_model = e.embedding_model
          AND j.embedding_kind = e.embedding_kind
          AND j.status IN ('pending', 'leased')
    )
    ORDER BY e.embedded_at ASC
    LIMIT p_limit;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

COMMENT ON FUNCTION vec_reembed_changed_jobs IS
  'Re-queues embedding jobs for existing rows where content_sha256 no longer matches the SHA-256 of content_text. Typically invoked after text builder changes to force re-embedding of affected rows.';
