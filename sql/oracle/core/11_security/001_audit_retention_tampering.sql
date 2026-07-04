-- object: audit_retention_tampering
-- folder: security
-- depends_on: proxy_data_retention_policies
-- source: sql/oracle.sql lines 853-867

BEGIN
    DBMS_FGA.ADD_POLICY(
        object_schema   => USER,
        object_name     => 'PROXY_DATA_RETENTION_POLICIES',
        policy_name     => 'AUDIT_RETENTION_TAMPERING',
        audit_condition => 'ENABLED = 0 OR RETAIN_DAYS < 30',
        statement_types => 'INSERT,UPDATE'
    );
    DBMS_FGA.ADD_POLICY(
        object_schema   => USER,
        object_name     => 'PROXY_DATA_RETENTION_POLICIES',
        policy_name     => 'AUDIT_RETENTION_DELETE',
        audit_condition => NULL,
        statement_types => 'DELETE'
    );
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -28101 THEN
            RAISE;
        END IF;
END;
/
