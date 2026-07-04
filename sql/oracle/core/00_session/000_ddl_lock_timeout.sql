-- object: oracle_ddl_lock_timeout
-- folder: session
-- depends_on: -
-- source: sql/oracle.sql lines 6-6

ALTER SESSION SET DDL_LOCK_TIMEOUT = 300;
