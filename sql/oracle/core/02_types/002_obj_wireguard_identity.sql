-- object: obj_wireguard_identity
-- folder: types
-- depends_on: -
-- source: sql/oracle.sql lines 15-21

CREATE OR REPLACE TYPE obj_wireguard_identity AS OBJECT (
    wg_pubkey       VARCHAR2(64),
    device_id       RAW(16),
    username        VARCHAR2(128),
    identity_source VARCHAR2(16)
);
/
