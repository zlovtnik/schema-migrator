-- object: proxy_security_ctx_pkg_spec
-- folder: packages
-- depends_on: -
-- source: sql/oracle.sql lines 811-814

CREATE OR REPLACE PACKAGE PROXY_SECURITY_CTX_PKG AS
    PROCEDURE SET_APPEND_ONLY_WRITER;
END PROXY_SECURITY_CTX_PKG;
/
