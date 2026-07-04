-- object: obj_traffic_metrics
-- folder: types
-- depends_on: -
-- source: sql/oracle.sql lines 23-28

CREATE OR REPLACE TYPE obj_traffic_metrics AS OBJECT (
    bytes_up    NUMBER(20,0),
    bytes_down  NUMBER(20,0),
    duration_ms NUMBER(12,0)
);
/
