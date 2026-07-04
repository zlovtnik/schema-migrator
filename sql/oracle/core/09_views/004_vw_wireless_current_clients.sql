-- object: vw_wireless_current_clients
-- folder: views
-- depends_on: wireless_client_inventory
-- source: sql/oracle.sql lines 953-960

CREATE OR REPLACE VIEW VW_WIRELESS_CURRENT_CLIENTS AS
SELECT inv.*
FROM WIRELESS_CLIENT_INVENTORY inv
JOIN (
    SELECT SENSOR_ID, MAX(SNAPSHOT_AT) AS LATEST_SNAP
    FROM WIRELESS_CLIENT_INVENTORY
    GROUP BY SENSOR_ID
) latest ON inv.SENSOR_ID = latest.SENSOR_ID AND inv.SNAPSHOT_AT = latest.LATEST_SNAP;
