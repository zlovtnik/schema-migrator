-- object: proxy_security_logon_ctx
-- folder: triggers
-- depends_on: proxy_security_ctx_pkg_body, proxy_security_ctx
-- source: sql/oracle.sql lines 831-836

CREATE OR REPLACE TRIGGER PROXY_SECURITY_LOGON_CTX
AFTER LOGON ON SCHEMA
BEGIN
    PROXY_SECURITY_CTX_PKG.SET_APPEND_ONLY_WRITER;
END;
/
