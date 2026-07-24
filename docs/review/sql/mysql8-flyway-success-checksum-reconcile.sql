-- Reconcile one known pre-release checksum drift on Oracle MySQL 8.
--
-- This operation is intentionally narrower than Flyway repair. It accepts only
-- the two exact successful history rows and legacy checksums listed below, and
-- it changes no schema or business data. The PowerShell entrypoint backs up the
-- history table before invoking this file.

DROP PROCEDURE IF EXISTS `hmdp_reconcile_mysql8_success_checksums`;
DELIMITER $$
CREATE PROCEDURE `hmdp_reconcile_mysql8_success_checksums`()
BEGIN
    DECLARE history_table_exists BIGINT DEFAULT 0;
    DECLARE target_rows BIGINT DEFAULT 0;
    DECLARE target_versions BIGINT DEFAULT 0;
    DECLARE legacy_contract_rows BIGINT DEFAULT 0;
    DECLARE missing_tables BIGINT DEFAULT 0;
    DECLARE missing_columns BIGINT DEFAULT 0;
    DECLARE invalid_indexes BIGINT DEFAULT 0;
    DECLARE approval_updates BIGINT DEFAULT 0;
    DECLARE shadow_updates BIGINT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF VERSION() LIKE '%MariaDB%' OR VERSION() NOT REGEXP '^8\\.' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Successful checksum reconciliation requires Oracle MySQL 8';
    END IF;

    SELECT COUNT(*)
    INTO history_table_exists
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'flyway_schema_history';

    IF history_table_exists <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'flyway_schema_history does not exist';
    END IF;

    SELECT COUNT(*), COUNT(DISTINCT version)
    INTO target_rows, target_versions
    FROM flyway_schema_history
    WHERE version IN ('20260720.03', '20260721.01');

    SELECT COUNT(*)
    INTO legacy_contract_rows
    FROM flyway_schema_history
    WHERE success = 1
      AND ((version = '20260720.03'
            AND description <=> 'approval outbox and index recovery'
            AND type <=> 'SQL'
            AND script <=> 'V20260720_03__approval_outbox_and_index_recovery.sql'
            AND checksum <=> -128447297)
        OR (version = '20260721.01'
            AND description <=> 'knowledge shadow index activation'
            AND type <=> 'SQL'
            AND script <=> 'V20260721_01__knowledge_shadow_index_activation.sql'
            AND checksum <=> -946922017));

    IF target_rows <> 2 OR target_versions <> 2 OR legacy_contract_rows <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Successful checksum reconciliation history contract failed';
    END IF;

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
        SELECT 'ai_approval_request' AS table_name, 'id' AS column_name
        UNION ALL SELECT 'ai_approval_request', 'tenant_id'
        UNION ALL SELECT 'ai_approval_request', 'workspace_id'
        UNION ALL SELECT 'ai_approval_request', 'run_id'
        UNION ALL SELECT 'ai_approval_request', 'node_run_id'
        UNION ALL SELECT 'ai_approval_request', 'tool_call_id'
        UNION ALL SELECT 'ai_approval_request', 'tool_id'
        UNION ALL SELECT 'ai_approval_request', 'tool_version'
        UNION ALL SELECT 'ai_approval_request', 'risk_level'
        UNION ALL SELECT 'ai_approval_request', 'input_hash'
        UNION ALL SELECT 'ai_approval_request', 'input_summary'
        UNION ALL SELECT 'ai_approval_request', 'requested_by'
        UNION ALL SELECT 'ai_approval_request', 'status'
        UNION ALL SELECT 'ai_approval_request', 'expires_at'
        UNION ALL SELECT 'ai_approval_request', 'created_by'
        UNION ALL SELECT 'ai_approval_request', 'updated_by'
        UNION ALL SELECT 'ai_approval_request', 'created_at'
        UNION ALL SELECT 'ai_approval_request', 'updated_at'
        UNION ALL SELECT 'ai_approval_request', 'deleted'
        UNION ALL SELECT 'ai_approval_decision', 'id'
        UNION ALL SELECT 'ai_approval_decision', 'tenant_id'
        UNION ALL SELECT 'ai_approval_decision', 'workspace_id'
        UNION ALL SELECT 'ai_approval_decision', 'approval_request_id'
        UNION ALL SELECT 'ai_approval_decision', 'decision'
        UNION ALL SELECT 'ai_approval_decision', 'reason'
        UNION ALL SELECT 'ai_approval_decision', 'decided_by'
        UNION ALL SELECT 'ai_approval_decision', 'decided_at'
        UNION ALL SELECT 'ai_approval_decision', 'created_by'
        UNION ALL SELECT 'ai_approval_decision', 'updated_by'
        UNION ALL SELECT 'ai_approval_decision', 'created_at'
        UNION ALL SELECT 'ai_approval_decision', 'updated_at'
        UNION ALL SELECT 'ai_approval_decision', 'deleted'
        UNION ALL SELECT 'ai_outbox_consumption', 'id'
        UNION ALL SELECT 'ai_outbox_consumption', 'outbox_event_id'
        UNION ALL SELECT 'ai_outbox_consumption', 'consumer_name'
        UNION ALL SELECT 'ai_outbox_consumption', 'status'
        UNION ALL SELECT 'ai_outbox_consumption', 'attempt'
        UNION ALL SELECT 'ai_outbox_consumption', 'failure_reason'
        UNION ALL SELECT 'ai_outbox_consumption', 'claimed_at'
        UNION ALL SELECT 'ai_outbox_consumption', 'claimed_by'
        UNION ALL SELECT 'ai_outbox_consumption', 'lease_until'
        UNION ALL SELECT 'ai_outbox_consumption', 'consumed_at'
        UNION ALL SELECT 'ai_outbox_consumption', 'created_at'
        UNION ALL SELECT 'ai_outbox_consumption', 'updated_at'
        UNION ALL SELECT 'ai_outbox_consumption', 'available_at'
        UNION ALL SELECT 'ai_outbox_dead_letter', 'id'
        UNION ALL SELECT 'ai_outbox_dead_letter', 'outbox_event_id'
        UNION ALL SELECT 'ai_outbox_dead_letter', 'consumer_name'
        UNION ALL SELECT 'ai_outbox_dead_letter', 'payload_json'
        UNION ALL SELECT 'ai_outbox_dead_letter', 'failure_reason'
        UNION ALL SELECT 'ai_outbox_dead_letter', 'attempt'
        UNION ALL SELECT 'ai_outbox_dead_letter', 'replay_status'
        UNION ALL SELECT 'ai_outbox_dead_letter', 'replayed_at'
        UNION ALL SELECT 'ai_outbox_dead_letter', 'created_at'
        UNION ALL SELECT 'ai_outbox_dead_letter', 'updated_at'
        UNION ALL SELECT 'ai_index_version', 'build_mode'
        UNION ALL SELECT 'ai_index_version', 'activated_at'
        UNION ALL SELECT 'ai_index_version', 'rollback_after'
        UNION ALL SELECT 'ai_index_version', 'shadow_of_index_version'
        UNION ALL SELECT 'ai_index_version', 'verification_json'
        UNION ALL SELECT 'ai_index_version', 'ready_at'
        UNION ALL SELECT 'ai_document_version', 'redacted_structure_json'
        UNION ALL SELECT 'ai_knowledge_base', 'active_index_version'
        UNION ALL SELECT 'ai_outbox_event', 'deduplication_key'
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
              AND table_name = 'ai_approval_decision'
              AND index_name = 'uk_ai_approval_decision'
        ) <> 1
           OR NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'ai_approval_decision'
              AND index_name = 'uk_ai_approval_decision'
              AND non_unique = 0
              AND seq_in_index = 1
              AND column_name = 'approval_request_id'
        )
        UNION ALL
        SELECT 1
        WHERE (
            SELECT COUNT(*)
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'ai_outbox_consumption'
              AND index_name = 'uk_ai_outbox_consumer'
        ) <> 2
           OR NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'ai_outbox_consumption'
              AND index_name = 'uk_ai_outbox_consumer'
              AND non_unique = 0
              AND seq_in_index = 1
              AND column_name = 'outbox_event_id'
        )
           OR NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'ai_outbox_consumption'
              AND index_name = 'uk_ai_outbox_consumer'
              AND non_unique = 0
              AND seq_in_index = 2
              AND column_name = 'consumer_name'
        )
        UNION ALL
        SELECT 1
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

    IF missing_tables > 0 OR missing_columns > 0 OR invalid_indexes > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Successful checksum reconciliation schema contract failed';
    END IF;

    START TRANSACTION;

    UPDATE flyway_schema_history
    SET checksum = 2143241596
    WHERE version = '20260720.03'
      AND description <=> 'approval outbox and index recovery'
      AND type <=> 'SQL'
      AND script <=> 'V20260720_03__approval_outbox_and_index_recovery.sql'
      AND checksum <=> -128447297
      AND success = 1;
    SET approval_updates = ROW_COUNT();

    UPDATE flyway_schema_history
    SET checksum = 814957484
    WHERE version = '20260721.01'
      AND description <=> 'knowledge shadow index activation'
      AND type <=> 'SQL'
      AND script <=> 'V20260721_01__knowledge_shadow_index_activation.sql'
      AND checksum <=> -946922017
      AND success = 1;
    SET shadow_updates = ROW_COUNT();

    IF approval_updates <> 1 OR shadow_updates <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Successful checksum reconciliation update count failed';
    END IF;

    COMMIT;

    SELECT version, description, checksum, success
    FROM flyway_schema_history
    WHERE version IN ('20260720.03', '20260721.01')
    ORDER BY installed_rank;
END$$
DELIMITER ;

CALL `hmdp_reconcile_mysql8_success_checksums`();
DROP PROCEDURE IF EXISTS `hmdp_reconcile_mysql8_success_checksums`;
