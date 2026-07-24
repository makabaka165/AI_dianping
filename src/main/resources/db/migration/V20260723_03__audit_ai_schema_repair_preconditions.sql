-- Audit the objects repaired by V20260723_01 before later application code relies on them.
-- Existing business rows are never deleted or merged. Operators must resolve a reported
-- duplicate and rerun Flyway.

DROP PROCEDURE IF EXISTS `hmdp_assert_ai_schema_repair_ready`;
DELIMITER $$
CREATE PROCEDURE `hmdp_assert_ai_schema_repair_ready`()
BEGIN
    DECLARE missing_columns BIGINT DEFAULT 0;
    DECLARE invalid_columns BIGINT DEFAULT 0;
    DECLARE duplicate_deduplication_keys BIGINT DEFAULT 0;
    DECLARE duplicate_dead_letters BIGINT DEFAULT 0;
    DECLARE invalid_indexes BIGINT DEFAULT 0;
    DECLARE audit_message VARCHAR(255);

    SELECT COUNT(*)
    INTO missing_columns
    FROM (
        SELECT 'ai_index_version' AS table_name, 'build_mode' AS column_name
        UNION ALL SELECT 'ai_index_version', 'activated_at'
        UNION ALL SELECT 'ai_index_version', 'rollback_after'
        UNION ALL SELECT 'ai_index_version', 'shadow_of_index_version'
        UNION ALL SELECT 'ai_index_version', 'verification_json'
        UNION ALL SELECT 'ai_index_version', 'ready_at'
        UNION ALL SELECT 'ai_document_version', 'redacted_structure_json'
        UNION ALL SELECT 'ai_knowledge_base', 'active_index_version'
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
    INTO invalid_columns
    FROM information_schema.columns c
    WHERE c.table_schema = DATABASE()
      AND (
          (c.table_name = 'ai_index_version' AND c.column_name = 'build_mode'
              AND (c.data_type <> 'varchar' OR c.character_maximum_length <> 32
                  OR c.is_nullable <> 'NO' OR COALESCE(c.column_default, '') <> 'SHADOW'))
          OR (c.table_name = 'ai_index_version' AND c.column_name = 'shadow_of_index_version'
              AND (c.data_type <> 'varchar' OR c.character_maximum_length <> 128
                  OR c.is_nullable <> 'YES'))
          OR (c.table_name = 'ai_index_version' AND c.column_name IN ('activated_at', 'rollback_after', 'ready_at')
              AND (c.data_type <> 'timestamp' OR c.datetime_precision <> 3
                  OR c.is_nullable <> 'YES'))
          OR (c.table_name = 'ai_index_version' AND c.column_name = 'verification_json'
              AND (c.data_type <> 'longtext' OR c.is_nullable <> 'YES'))
          OR (c.table_name = 'ai_document_version' AND c.column_name = 'redacted_structure_json'
              AND (c.data_type <> 'longtext' OR c.is_nullable <> 'YES'))
          OR (c.table_name = 'ai_knowledge_base' AND c.column_name = 'active_index_version'
              AND (c.data_type <> 'varchar' OR c.character_maximum_length <> 128
                  OR c.is_nullable <> 'YES'))
          OR (c.table_name = 'ai_outbox_event' AND c.column_name = 'deduplication_key'
              AND (c.data_type <> 'varchar' OR c.character_maximum_length <> 255
                  OR c.is_nullable <> 'YES'))
          OR (c.table_name = 'ai_outbox_consumption' AND c.column_name = 'available_at'
              AND (c.data_type <> 'timestamp' OR c.datetime_precision <> 3
                  OR c.is_nullable <> 'NO'))
      );

    SELECT COUNT(*)
    INTO duplicate_deduplication_keys
    FROM (
        SELECT deduplication_key
        FROM ai_outbox_event
        WHERE deduplication_key IS NOT NULL
        GROUP BY deduplication_key
        HAVING COUNT(*) > 1
    ) duplicate_keys;

    SELECT COUNT(*)
    INTO duplicate_dead_letters
    FROM (
        SELECT outbox_event_id, consumer_name
        FROM ai_outbox_dead_letter
        GROUP BY outbox_event_id, consumer_name
        HAVING COUNT(*) > 1
    ) duplicate_letters;

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

    IF missing_columns > 0 OR invalid_columns > 0 OR duplicate_deduplication_keys > 0
        OR duplicate_dead_letters > 0 OR invalid_indexes > 0 THEN
        SET audit_message = CONCAT(
            'AI schema repair audit failed: missing=', missing_columns,
            ', invalid_columns=', invalid_columns,
            ', duplicate_dedup=', duplicate_deduplication_keys,
            ', duplicate_dead_letter=', duplicate_dead_letters,
            ', invalid_indexes=', invalid_indexes,
            '; resolve manually before migration'
        );
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = audit_message;
    END IF;
END$$
DELIMITER ;

CALL `hmdp_assert_ai_schema_repair_ready`();
DROP PROCEDURE IF EXISTS `hmdp_assert_ai_schema_repair_ready`;
