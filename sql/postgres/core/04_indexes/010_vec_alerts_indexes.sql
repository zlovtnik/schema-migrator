-- object: vec_alerts indexes
-- folder: indexes
-- depends_on: vec_alerts
CREATE INDEX IF NOT EXISTS idx_vec_alerts_type_created
    ON vec_alerts (alert_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_vec_alerts_mac
    ON vec_alerts (source_mac);

CREATE INDEX IF NOT EXISTS idx_vec_alerts_created
    ON vec_alerts (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_vec_alerts_metadata_bssid
    ON vec_alerts ((metadata->>'bssid'))
    WHERE metadata->>'bssid' IS NOT NULL;

-- V022: Add composite index for vec_alerts near-duplicate dedupe predicate
--
-- check_near_duplicates filters by alert_type, source_mac, and recent
-- created_at. A single composite index serves that predicate more efficiently
-- than the three separate indexes added in V020.
--
-- The existing indexes (idx_vec_alerts_type_created, idx_vec_alerts_mac,
-- idx_vec_alerts_created) are left in place since they may serve other
-- query patterns.

CREATE INDEX IF NOT EXISTS idx_vec_alerts_type_mac_created
    ON vec_alerts (alert_type, source_mac, created_at DESC);
