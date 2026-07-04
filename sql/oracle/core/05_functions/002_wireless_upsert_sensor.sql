-- object: wireless_upsert_sensor
-- folder: functions
-- depends_on: wireless_sensors
-- source: sql/oracle.sql lines 772-794

CREATE OR REPLACE PROCEDURE WIRELESS_UPSERT_SENSOR (
    p_sensor_id IN VARCHAR2,
    p_location_id IN VARCHAR2,
    p_interface IN VARCHAR2,
    p_reg_domain IN VARCHAR2,
    p_observed_at IN TIMESTAMP WITH TIME ZONE
) AS
BEGIN
    MERGE INTO WIRELESS_SENSORS tgt
    USING (SELECT 1 FROM DUAL) dummy
    ON (tgt.SENSOR_ID = p_sensor_id)
    WHEN MATCHED THEN UPDATE SET
        tgt.LOCATION_ID = p_location_id,
        tgt.INTERFACE = p_interface,
        tgt.REG_DOMAIN = p_reg_domain,
        tgt.LAST_SEEN_AT = p_observed_at
    WHEN NOT MATCHED THEN INSERT (
        SENSOR_ID, LOCATION_ID, INTERFACE, REG_DOMAIN, FIRST_SEEN_AT, LAST_SEEN_AT
    ) VALUES (
        p_sensor_id, p_location_id, p_interface, p_reg_domain, p_observed_at, p_observed_at
    );
    COMMIT;
END WIRELESS_UPSERT_SENSOR;
/
