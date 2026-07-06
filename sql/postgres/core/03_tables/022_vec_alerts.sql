-- object: vec_alerts
-- folder: tables
-- depends_on: vec_similarity_pairs
-- V020: Create vec_alerts table for actionable alert feed
--
-- Stores alerts generated from embedding analysis:
-- - near_duplicate_cluster: when a device exceeds the near-duplicate threshold
-- - behaviour_anomaly: when a behaviour window deviates from baseline
-- - new_device: first-seen device with embedding profile
-- - device_fingerprint_change: WPS identity or fingerprint shift

CREATE TABLE IF NOT EXISTS vec_alerts (
    id BIGSERIAL PRIMARY KEY,
    alert_type TEXT NOT NULL,
    source_mac TEXT,
    sensor_id TEXT,
    location_id TEXT,
    score DOUBLE PRECISION,
    explanation_text TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE vec_alerts IS
  'Actionable alerts generated from embedding analysis (near-duplicate, behaviour anomaly, etc.).';
