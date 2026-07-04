-- object: obj_network_endpoint
-- folder: types
-- depends_on: -
-- source: sql/oracle.sql lines 8-13

CREATE OR REPLACE TYPE obj_network_endpoint AS OBJECT (
    ip_address VARCHAR2(45),
    port       NUMBER(5,0),
    hostname   VARCHAR2(253)
);
/
