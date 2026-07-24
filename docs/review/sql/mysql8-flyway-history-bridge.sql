-- One-time bridge for Oracle MySQL 8 installations only.
--
-- The historical V20260720_03 and V20260721_01 files use MariaDB-only
-- `ADD COLUMN IF NOT EXISTS` syntax. Do not edit those published files and do
-- not run this bridge on MariaDB. The companion db/mysql8-compat scripts must
-- have completed successfully before this file is run.
--
-- This file deliberately changes only failed/missing rows for the two exact
-- versions. Back up flyway_schema_history before running it.

DROP PROCEDURE IF EXISTS `hmdp_bridge_mysql8_flyway_history`;
DELIMITER $$
CREATE PROCEDURE `hmdp_bridge_mysql8_flyway_history`()
BEGIN
    DECLARE history_table_exists BIGINT DEFAULT 0;
    DECLARE base_rows BIGINT DEFAULT 0;
    DECLARE base_rank INT DEFAULT 0;
    DECLARE invalid_history BIGINT DEFAULT 0;
    DECLARE duplicate_history BIGINT DEFAULT 0;
    DECLARE incomplete_history BIGINT DEFAULT 0;
    DECLARE unexpected_success BIGINT DEFAULT 0;
    DECLARE failed_other BIGINT DEFAULT 0;
    DECLARE missing_tables BIGINT DEFAULT 0;
    DECLARE missing_columns BIGINT DEFAULT 0;
    DECLARE invalid_indexes BIGINT DEFAULT 0;
    DECLARE target_successes BIGINT DEFAULT 0;
    DECLARE next_rank INT DEFAULT 0;

    IF VERSION() LIKE '%MariaDB%' OR VERSION() NOT REGEXP '^8\\.' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'MySQL 8 bridge requires Oracle MySQL 8';
    END IF;

    SELECT COUNT(*)
    INTO history_table_exists
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'flyway_schema_history';

    IF history_table_exists <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'flyway_schema_history does not exist; run Flyway to 20260720.02 first';
    END IF;

    SELECT COUNT(*), COALESCE(MAX(installed_rank), 0)
    INTO base_rows, base_rank
    FROM flyway_schema_history
    WHERE version = '20260720.02' AND success = 1;

    IF base_rows <> 1 OR base_rank = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Flyway base 20260720.02 is not successful';
    END IF;

    SELECT COUNT(*)
    INTO invalid_history
    FROM flyway_schema_history
    WHERE (version = '20260720.03'
           AND NOT (description <=> 'approval outbox and index recovery'
                   AND type <=> 'SQL'
                   AND script <=> 'V20260720_03__approval_outbox_and_index_recovery.sql'
                   AND checksum <=> 2143241596))
       OR (version = '20260721.01'
           AND NOT (description <=> 'knowledge shadow index activation'
                   AND type <=> 'SQL'
                   AND script <=> 'V20260721_01__knowledge_shadow_index_activation.sql'
                   AND checksum <=> 814957484));

    SELECT COUNT(*)
    INTO duplicate_history
    FROM (
        SELECT version
        FROM flyway_schema_history
        WHERE version IN ('20260720.03', '20260721.01')
        GROUP BY version
        HAVING COUNT(*) > 1
    ) duplicate_versions;

    SELECT COUNT(*)
    INTO incomplete_history
    FROM (
        SELECT 1 AS inconsistent
        WHERE EXISTS (
            SELECT 1 FROM flyway_schema_history
            WHERE version = '20260721.01' AND success = 1
        )
        AND NOT EXISTS (
            SELECT 1 FROM flyway_schema_history
            WHERE version = '20260720.03' AND success = 1
        )
    ) incomplete_versions;

    SELECT COUNT(*)
    INTO unexpected_success
    FROM flyway_schema_history
    WHERE success = 1
      AND installed_rank > base_rank
      AND version NOT IN ('20260720.03', '20260721.01');

    SELECT COUNT(*)
    INTO failed_other
    FROM flyway_schema_history
    WHERE success = 0
      AND version NOT IN ('20260720.03', '20260721.01');

    SELECT COUNT(*)
    INTO missing_tables
    FROM (
        SELECT 'ai_approval_request' AS table_name
        UNION ALL SELECT 'ai_approval_decision'
        UNION ALL SELECT 'ai_outbox_consumption'
        UNION ALL SELECT 'ai_outbox_dead_letter'
    ) required_tables
    WHERE NOT EXISTS (
        SELECT 1
        FROM information_schema.tables t
        WHERE t.table_schema = DATABASE()
          AND t.table_name = required_tables.table_name
    );

    SELECT COUNT(*)
    INTO missing_columns
    FROM (
        SELECT 'ai_index_version' AS table_name, 'build_mode' AS column_name
        UNION ALL SELECT 'ai_index_version', 'activated_at'
        UNION ALL SELECT 'ai_index_version', 'rollback_after'
        UNION ALL SELECT 'ai_knowledge_base', 'active_index_version'
        UNION ALL SELECT 'ai_index_version', 'shadow_of_index_version'
        UNION ALL SELECT 'ai_index_version', 'verification_json'
        UNION ALL SELECT 'ai_index_version', 'ready_at'
        UNION ALL SELECT 'ai_document_version', 'redacted_structure_json'
        UNION ALL SELECT 'ai_outbox_event', 'deduplication_key'
        UNION ALL SELECT 'ai_outbox_consumption', 'available_at'
    ) required_columns
    WHERE NOT EXISTS (
        SELECT 1
        FROM information_schema.columns c
        WHERE c.table_schema = DATABASE()
          AND c.table_name = required_columns.table_name
          AND c.column_name = required_columns.column_name
    );

    SELECT COUNT(*)
    INTO invalid_indexes
    FROM (
        SELECT 1 AS invalid_definition
        WHERE (
            SELECT COUNT(*)
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'ai_outbox_event'
              AND index_name = 'uk_ai_outbox_deduplication'
        ) <> 1
           OR NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'ai_outbox_event'
              AND index_name = 'uk_ai_outbox_deduplication'
              AND non_unique = 0
              AND seq_in_index = 1
              AND column_name = 'deduplication_key'
        )
        UNION ALL
        SELECT 1
        WHERE (
            SELECT COUNT(*)
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'ai_outbox_dead_letter'
              AND index_name = 'uk_ai_outbox_dead_letter_consumer'
        ) <> 2
           OR NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'ai_outbox_dead_letter'
              AND index_name = 'uk_ai_outbox_dead_letter_consumer'
              AND non_unique = 0
              AND seq_in_index = 1
              AND column_name = 'outbox_event_id'
        )
           OR NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'ai_outbox_dead_letter'
              AND index_name = 'uk_ai_outbox_dead_letter_consumer'
              AND non_unique = 0
              AND seq_in_index = 2
              AND column_name = 'consumer_name'
        )
    ) invalid_definitions;

    IF invalid_history > 0 OR duplicate_history > 0 OR incomplete_history > 0
        OR unexpected_success > 0 OR failed_other > 0
        OR missing_tables > 0 OR missing_columns > 0 OR invalid_indexes > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'MySQL 8 bridge preconditions failed; inspect schema/history';
    END IF;

    -- A failed Flyway row is an execution marker, not business data. Remove only
    -- the two known failed versions after all schema checks have passed.
    DELETE FROM flyway_schema_history
    WHERE success = 0
      AND ((version = '20260720.03'
            AND description <=> 'approval outbox and index recovery'
            AND type <=> 'SQL'
            AND script <=> 'V20260720_03__approval_outbox_and_index_recovery.sql'
            AND checksum <=> 2143241596)
        OR (version = '20260721.01'
            AND description <=> 'knowledge shadow index activation'
            AND type <=> 'SQL'
            AND script <=> 'V20260721_01__knowledge_shadow_index_activation.sql'
            AND checksum <=> 814957484));

    SELECT COALESCE(MAX(installed_rank), 0) + 1
    INTO next_rank
    FROM flyway_schema_history;

    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE version = '20260720.03' AND success = 1
    ) THEN
        INSERT INTO flyway_schema_history
            (installed_rank, version, description, type, script, checksum,
             installed_by, execution_time, success)
        VALUES
            (next_rank, '20260720.03', 'approval outbox and index recovery', 'SQL',
             'V20260720_03__approval_outbox_and_index_recovery.sql', 2143241596,
             SUBSTRING_INDEX(CURRENT_USER(), '@', 1), 0, 1);
        SET next_rank = next_rank + 1;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE version = '20260721.01' AND success = 1
    ) THEN
        INSERT INTO flyway_schema_history
            (installed_rank, version, description, type, script, checksum,
             installed_by, execution_time, success)
        VALUES
            (next_rank, '20260721.01', 'knowledge shadow index activation', 'SQL',
             'V20260721_01__knowledge_shadow_index_activation.sql', 814957484,
              SUBSTRING_INDEX(CURRENT_USER(), '@', 1), 0, 1);
    END IF;

    SELECT COUNT(*)
    INTO target_successes
    FROM flyway_schema_history
    WHERE version IN ('20260720.03', '20260721.01')
      AND success = 1;

    IF target_successes <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'MySQL 8 bridge did not register both historical migrations';
    END IF;

    SELECT version, description, checksum, success
    FROM flyway_schema_history
    WHERE version IN ('20260720.03', '20260721.01')
    ORDER BY installed_rank;
END$$
DELIMITER ;

CALL `hmdp_bridge_mysql8_flyway_history`();
DROP PROCEDURE IF EXISTS `hmdp_bridge_mysql8_flyway_history`;
