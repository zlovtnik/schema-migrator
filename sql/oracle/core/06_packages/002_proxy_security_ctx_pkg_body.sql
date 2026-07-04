-- object: proxy_security_ctx_pkg_body
-- folder: packages
-- depends_on: proxy_security_ctx_pkg_spec, proxy_security_ctx
-- source: sql/oracle.sql lines 818-829

CREATE OR REPLACE PACKAGE BODY PROXY_SECURITY_CTX_PKG AS
    PROCEDURE SET_APPEND_ONLY_WRITER AS
    BEGIN
        DBMS_SESSION.SET_CONTEXT('PROXY_SECURITY_CTX', 'APPEND_ONLY_WRITER', NULL);
    END SET_APPEND_ONLY_WRITER;
END PROXY_SECURITY_CTX_PKG;
/
