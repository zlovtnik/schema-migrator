-- object: v_obj_proxy_events
-- folder: views
-- depends_on: proxy_events, obj_wireguard_identity, obj_network_endpoint, obj_traffic_metrics
-- source: sql/oracle.sql lines 898-917

CREATE OR REPLACE VIEW V_OBJ_PROXY_EVENTS AS
SELECT
    ID,
    BATCH_ID,
    EVENT_TIME,
    EVENT_TIMESTAMP_UTC,
    EVENT_TYPE,
    HOST,
    obj_wireguard_identity(WG_PUBKEY, DEVICE_ID, NULL, IDENTITY_SOURCE) AS client_identity,
    obj_network_endpoint(NULL, NULL, HOST) AS destination,
    obj_traffic_metrics(BYTES_UP, BYTES_DOWN, DURATION_MS) AS traffic,
    STATUS_CODE,
    BLOCKED,
    OBFUSCATION_PROFILE,
    CORRELATION_ID,
    PARENT_EVENT_ID,
    EVENT_SEQUENCE,
    REASON,
    RAW_JSON
FROM PROXY_EVENTS;
