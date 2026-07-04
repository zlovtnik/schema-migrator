-- object: wireless_gather_stats_nightly
-- folder: scheduler
-- depends_on: wireless_audit_frames, wireless_bandwidth_windows, wireless_client_inventory
-- source: sql/oracle.sql lines 962-978

BEGIN
    DBMS_SCHEDULER.CREATE_JOB (
        job_name => 'WIRELESS_GATHER_STATS_NIGHTLY',
        job_type => 'PLSQL_BLOCK',
        job_action => 'BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, ''WIRELESS_AUDIT_FRAMES'', granularity=>''AUTO'', options=>''GATHER AUTO'', degree=>4); DBMS_STATS.GATHER_TABLE_STATS(USER, ''WIRELESS_BANDWIDTH_WINDOWS'', granularity=>''AUTO'', options=>''GATHER AUTO'', degree=>4); DBMS_STATS.GATHER_TABLE_STATS(USER, ''WIRELESS_CLIENT_INVENTORY'', granularity=>''AUTO'', options=>''GATHER AUTO'', degree=>2); END;',
        start_date => SYSTIMESTAMP,
        repeat_interval => 'FREQ=DAILY;BYHOUR=3;BYMINUTE=0',
        enabled => TRUE,
        comments => 'Nightly CBO stats refresh for wireless tables'
    );
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -27477 THEN
            RAISE;
        END IF;
END;
/
