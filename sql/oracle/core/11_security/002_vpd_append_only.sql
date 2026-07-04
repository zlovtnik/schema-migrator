-- object: vpd_append_only
-- folder: security
-- depends_on: proxy_append_only_policy, proxy_events, proxy_payload_audit, proxy_blocklist_audit, proxy_db_query_log, wireless_audit_frames, wireless_alerts_ledger
-- source: sql/oracle.sql lines 869-896

DECLARE
    PROCEDURE add_append_only_policy(p_table_name IN VARCHAR2) AS
    BEGIN
        DBMS_RLS.ADD_POLICY(
            object_schema   => USER,
            object_name     => p_table_name,
            policy_name     => 'VPD_APPEND_ONLY',
            function_schema => USER,
            policy_function => 'PROXY_APPEND_ONLY_POLICY',
            statement_types => 'SELECT,UPDATE,DELETE',
            update_check    => FALSE,
            enable          => TRUE
        );
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE != -28101 THEN
                RAISE;
            END IF;
    END;
BEGIN
    add_append_only_policy('PROXY_EVENTS');
    add_append_only_policy('PROXY_PAYLOAD_AUDIT');
    add_append_only_policy('PROXY_BLOCKLIST_AUDIT');
    add_append_only_policy('PROXY_DB_QUERY_LOG');
    add_append_only_policy('WIRELESS_AUDIT_FRAMES');
    add_append_only_policy('WIRELESS_ALERTS_LEDGER');
END;
/
