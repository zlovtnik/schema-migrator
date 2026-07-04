-- =============================================================================
-- nuclear_drop.sql
--
-- PL/SQL block to drop all objects from the current Oracle schema.
--
-- This script removes database objects in dependency order:
--   1. Packages (bodies first, then specs)
--   2. Types (with FORCE to handle dependencies)
--   3. Tables (with CASCADE CONSTRAINTS)
--   4. Views, sequences, functions, procedures, triggers
--   5. Other object types
--
-- Usage:
--   sqlplus user/password @sql/nuclear_drop.sql
--   Paste the entire block into SQL*Plus or SQLcl
--
-- WARNING: This deletes all objects owned by the connected schema.
-- =============================================================================

SET SERVEROUTPUT ON
SET ECHO OFF
SET FEEDBACK OFF

DECLARE
    v_object_type   VARCHAR2(30);
    v_error_count   INTEGER := 0;
    v_success_count INTEGER := 0;

    TYPE t_object_types IS TABLE OF VARCHAR2(30);
    v_types CONSTANT t_object_types := t_object_types(
        'PACKAGE BODY',
        'PACKAGE',
        'TYPE',
        'TABLE',
        'VIEW',
        'SEQUENCE',
        'FUNCTION',
        'PROCEDURE',
        'TRIGGER',
        'SYNONYM',
        'MATERIALIZED VIEW',
        'DIMENSION',
        'CLUSTER'
    );

    FUNCTION quote_name(p_name IN VARCHAR2) RETURN VARCHAR2 IS
    BEGIN
        RETURN '"' || REPLACE(p_name, '"', '""') || '"';
    END quote_name;

    PROCEDURE purge_recyclebin IS
    BEGIN
        BEGIN
            EXECUTE IMMEDIATE 'PURGE RECYCLEBIN';
            DBMS_OUTPUT.PUT_LINE('[OK] Purged recycle bin');
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('[WARN] Failed to purge recycle bin: ' || SQLERRM);
        END;
    END purge_recyclebin;

    PROCEDURE drop_object(p_type IN VARCHAR2, p_name IN VARCHAR2) IS
        v_sql VARCHAR2(1000);
    BEGIN
        CASE p_type
            WHEN 'PACKAGE BODY' THEN
                v_sql := 'DROP PACKAGE BODY ' || quote_name(p_name);
            WHEN 'PACKAGE' THEN
                v_sql := 'DROP PACKAGE ' || quote_name(p_name);
            WHEN 'TYPE' THEN
                v_sql := 'DROP TYPE ' || quote_name(p_name) || ' FORCE';
            WHEN 'TABLE' THEN
                v_sql := 'DROP TABLE ' || quote_name(p_name) || ' CASCADE CONSTRAINTS PURGE';
            WHEN 'VIEW' THEN
                v_sql := 'DROP VIEW ' || quote_name(p_name) || ' CASCADE CONSTRAINTS';
            WHEN 'SEQUENCE' THEN
                v_sql := 'DROP SEQUENCE ' || quote_name(p_name);
            WHEN 'FUNCTION' THEN
                v_sql := 'DROP FUNCTION ' || quote_name(p_name);
            WHEN 'PROCEDURE' THEN
                v_sql := 'DROP PROCEDURE ' || quote_name(p_name);
            WHEN 'TRIGGER' THEN
                v_sql := 'DROP TRIGGER ' || quote_name(p_name);
            WHEN 'SYNONYM' THEN
                v_sql := 'DROP SYNONYM ' || quote_name(p_name);
            WHEN 'MATERIALIZED VIEW' THEN
                v_sql := 'DROP MATERIALIZED VIEW ' || quote_name(p_name);
            WHEN 'DIMENSION' THEN
                v_sql := 'DROP DIMENSION ' || quote_name(p_name);
            WHEN 'CLUSTER' THEN
                v_sql := 'DROP CLUSTER ' || quote_name(p_name) || ' INCLUDING TABLES CASCADE CONSTRAINTS';
            ELSE
                RETURN;
        END CASE;

        EXECUTE IMMEDIATE v_sql;
        v_success_count := v_success_count + 1;
        DBMS_OUTPUT.PUT_LINE('[OK] Dropped ' || p_type || ': ' || p_name);
    EXCEPTION
        WHEN OTHERS THEN
            v_error_count := v_error_count + 1;
            DBMS_OUTPUT.PUT_LINE('[FAIL] Failed to drop ' || p_type || ' ' || p_name || ': ' || SQLERRM);
    END drop_object;

BEGIN
    DBMS_OUTPUT.PUT_LINE('========================================');
    DBMS_OUTPUT.PUT_LINE('Starting cleanup of all objects...');
    DBMS_OUTPUT.PUT_LINE('Current user: ' || USER);
    DBMS_OUTPUT.PUT_LINE('========================================');
    DBMS_OUTPUT.PUT_LINE(' ');

    purge_recyclebin;
    DBMS_OUTPUT.PUT_LINE(' ');

    DBMS_OUTPUT.PUT_LINE('Processing scheduler jobs...');
    FOR job_rec IN (
        SELECT job_name
        FROM user_scheduler_jobs
        ORDER BY job_name
    ) LOOP
        BEGIN
            DBMS_SCHEDULER.DROP_JOB(job_rec.job_name, TRUE);
            v_success_count := v_success_count + 1;
            DBMS_OUTPUT.PUT_LINE('[OK] Dropped JOB: ' || job_rec.job_name);
        EXCEPTION
            WHEN OTHERS THEN
                v_error_count := v_error_count + 1;
                DBMS_OUTPUT.PUT_LINE('[FAIL] Failed to drop JOB ' || job_rec.job_name || ': ' || SQLERRM);
        END;
    END LOOP;

    FOR idx IN v_types.FIRST .. v_types.LAST LOOP
        v_object_type := v_types(idx);

        DBMS_OUTPUT.PUT_LINE('Processing ' || v_object_type || ' objects...');

        FOR obj_rec IN (
            SELECT object_name
            FROM user_objects
            WHERE object_type = v_object_type
              AND object_name NOT LIKE 'BIN$%'
              AND NOT (
                  object_type = 'SEQUENCE'
                  AND object_name LIKE 'ISEQ$$\_%' ESCAPE '\'
              )
            ORDER BY object_name
        ) LOOP
            drop_object(v_object_type, obj_rec.object_name);
        END LOOP;
    END LOOP;

    DBMS_OUTPUT.PUT_LINE(' ');
    DBMS_OUTPUT.PUT_LINE('========================================');
    DBMS_OUTPUT.PUT_LINE('Cleanup Summary:');
    DBMS_OUTPUT.PUT_LINE('  Objects dropped successfully: ' || v_success_count);
    DBMS_OUTPUT.PUT_LINE('  Objects failed to drop:       ' || v_error_count);
    DBMS_OUTPUT.PUT_LINE('========================================');

    IF v_error_count > 0 THEN
        DBMS_OUTPUT.PUT_LINE('WARNING: Some objects could not be dropped.');
        DBMS_OUTPUT.PUT_LINE('Check the errors above and retry if necessary.');
    ELSE
        DBMS_OUTPUT.PUT_LINE('SUCCESS: All droppable objects have been dropped.');
    END IF;

    DBMS_OUTPUT.PUT_LINE(' ');
    purge_recyclebin;

    DBMS_OUTPUT.PUT_LINE(' ');
    DBMS_OUTPUT.PUT_LINE('Remaining objects in schema:');
    DECLARE
        v_remaining_count INTEGER;
        v_identity_sequence_count INTEGER;
    BEGIN
        SELECT COUNT(*) INTO v_remaining_count
        FROM user_objects
        WHERE object_name NOT LIKE 'BIN$%'
          AND NOT (
              object_type = 'SEQUENCE'
              AND object_name LIKE 'ISEQ$$\_%' ESCAPE '\'
          );

        SELECT COUNT(*) INTO v_identity_sequence_count
        FROM user_objects
        WHERE object_type = 'SEQUENCE'
          AND object_name LIKE 'ISEQ$$\_%' ESCAPE '\';

        DBMS_OUTPUT.PUT_LINE('Total remaining droppable objects: ' || v_remaining_count);
        DBMS_OUTPUT.PUT_LINE('System-generated identity sequences ignored: ' || v_identity_sequence_count);
    END;

EXCEPTION
    WHEN OTHERS THEN
        DBMS_OUTPUT.PUT_LINE('FATAL ERROR: ' || SQLERRM);
        RAISE;
END;
/
