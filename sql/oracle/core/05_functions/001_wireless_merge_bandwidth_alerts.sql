-- object: wireless_merge_bandwidth_alerts
-- folder: functions
-- depends_on: wireless_alerts, wireless_bandwidth_windows
-- source: sql/oracle.sql lines 714-770

CREATE OR REPLACE PROCEDURE WIRELESS_MERGE_BANDWIDTH_ALERTS (p_batch_id IN VARCHAR2) AS
BEGIN
    MERGE INTO WIRELESS_ALERTS tgt
    USING (
        WITH batch_keys AS (
            SELECT DISTINCT SENSOR_ID, SOURCE_MAC, DESTINATION_BSSID, TRUNC(CAST(WINDOW_START AS DATE)) AS ALERT_DATE
            FROM WIRELESS_BANDWIDTH_WINDOWS
            WHERE BATCH_ID = p_batch_id AND THRESHOLD_EXCEEDED = 1
        )
        SELECT
            'bandwidth_threshold' AS ALERT_TYPE,
            p_batch_id AS BATCH_ID,
            MIN(w.ROW_SEQUENCE) AS ROW_SEQUENCE,
            TRUNC(CAST(w.WINDOW_START AS DATE)) AS ALERT_DATE,
            MIN(w.WINDOW_START) AS DETECTED_AT,
            w.SENSOR_ID,
            MAX(w.LOCATION_ID) KEEP (DENSE_RANK LAST ORDER BY w.WINDOW_START) AS LOCATION_ID,
            w.SOURCE_MAC AS PRIMARY_MAC,
            w.DESTINATION_BSSID AS SECONDARY_MAC,
            MAX(w.SSID) KEEP (DENSE_RANK LAST ORDER BY w.WINDOW_START) AS SSID,
            SUM(w.BYTES) AS BYTES,
            JSON_OBJECT(
              'first_window_start' VALUE TO_CHAR(MIN(w.WINDOW_START), 'YYYY-MM-DD"T"HH24:MI:SSTZH:TZM'),
              'last_window_start' VALUE TO_CHAR(MAX(w.WINDOW_START), 'YYYY-MM-DD"T"HH24:MI:SSTZH:TZM'),
              'window_count' VALUE COUNT(*),
              'max_bytes_per_window' VALUE MAX(w.BYTES)
              RETURNING JSON
            ) AS DETAILS_JSON
        FROM WIRELESS_BANDWIDTH_WINDOWS w
        JOIN batch_keys k ON k.SENSOR_ID = w.SENSOR_ID
          AND k.SOURCE_MAC = w.SOURCE_MAC
          AND k.DESTINATION_BSSID = w.DESTINATION_BSSID
          AND k.ALERT_DATE = TRUNC(CAST(w.WINDOW_START AS DATE))
        WHERE w.BATCH_ID = p_batch_id
          AND w.THRESHOLD_EXCEEDED = 1
        GROUP BY w.SENSOR_ID, w.SOURCE_MAC, w.DESTINATION_BSSID, TRUNC(CAST(w.WINDOW_START AS DATE))
    ) src
    ON (tgt.ALERT_TYPE = src.ALERT_TYPE AND tgt.SENSOR_ID = src.SENSOR_ID AND tgt.PRIMARY_MAC = src.PRIMARY_MAC AND tgt.SECONDARY_MAC = src.SECONDARY_MAC AND tgt.ALERT_DATE = src.ALERT_DATE)
    WHEN MATCHED THEN UPDATE SET
        tgt.BATCH_ID = src.BATCH_ID,
        tgt.ROW_SEQUENCE = src.ROW_SEQUENCE,
        tgt.DETECTED_AT = src.DETECTED_AT,
        tgt.LOCATION_ID = src.LOCATION_ID,
        tgt.SSID = src.SSID,
        tgt.BYTES = src.BYTES,
        tgt.DETAILS_JSON = src.DETAILS_JSON,
        tgt.UPDATED_AT = SYSTIMESTAMP
    WHEN NOT MATCHED THEN INSERT (
        ALERT_TYPE, BATCH_ID, ROW_SEQUENCE, ALERT_DATE, DETECTED_AT, SENSOR_ID,
        LOCATION_ID, PRIMARY_MAC, SECONDARY_MAC, SSID, BYTES, DETAILS_JSON
    ) VALUES (
        src.ALERT_TYPE, src.BATCH_ID, src.ROW_SEQUENCE, src.ALERT_DATE, src.DETECTED_AT,
        src.SENSOR_ID, src.LOCATION_ID, src.PRIMARY_MAC, src.SECONDARY_MAC,
        src.SSID, src.BYTES, src.DETAILS_JSON
    );
    COMMIT;
END WIRELESS_MERGE_BANDWIDTH_ALERTS;
/
