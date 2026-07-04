-- object: proxy_append_only_policy
-- folder: functions
-- depends_on: proxy_security_ctx
-- source: sql/oracle.sql lines 838-851

CREATE OR REPLACE FUNCTION PROXY_APPEND_ONLY_POLICY (
    schema_name IN VARCHAR2,
    object_name IN VARCHAR2
) RETURN VARCHAR2 AS
BEGIN
    IF SYS_CONTEXT('USERENV', 'SESSION_USER') IN ('PROXY_INGEST', 'PROXY_INGEST_ROLE')
       OR SYS_CONTEXT('SYS_SESSION_ROLES', 'PROXY_INGEST_ROLE') = 'TRUE' THEN
        RETURN '1=2';
    END IF;

    RETURN NULL;
END PROXY_APPEND_ONLY_POLICY;
/
