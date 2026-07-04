-- object: vw_wireless_active_threats
-- folder: views
-- depends_on: wireless_alerts
-- source: sql/oracle.sql lines 919-932

CREATE OR REPLACE VIEW VW_WIRELESS_ACTIVE_THREATS AS
SELECT ALERT_TYPE AS THREAT_TYPE,
       CASE ALERT_TYPE WHEN 'bandwidth_threshold' THEN 'CRITICAL' WHEN 'rogue_ap' THEN 'HIGH' WHEN 'deauth_flood' THEN 'HIGH' ELSE 'MEDIUM' END AS SEVERITY,
       DETECTED_AT AS EVENT_AT,
       SENSOR_ID,
       LOCATION_ID,
       PRIMARY_MAC,
       SSID,
       BYTES,
       ALERT_PK,
       ACKNOWLEDGED,
       CASE ALERT_TYPE WHEN 'bandwidth_threshold' THEN 1 WHEN 'rogue_ap' THEN 2 WHEN 'deauth_flood' THEN 2 ELSE 3 END AS SORT_PRIORITY
FROM WIRELESS_ALERTS
WHERE ACKNOWLEDGED = 0;
