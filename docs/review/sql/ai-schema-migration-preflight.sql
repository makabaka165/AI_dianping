-- Run this non-destructive audit against the target MySQL 8 database before Flyway migration.
-- Every duplicate/collision query must return zero rows. Review custom-name rows manually.

SELECT table_name, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND (
      (table_name = 'ai_outbox_event' AND column_name = 'deduplication_key')
      OR (table_name = 'ai_outbox_consumption' AND column_name = 'available_at')
      OR (table_name = 'ai_index_version' AND column_name IN (
          'build_mode', 'activated_at', 'rollback_after', 'shadow_of_index_version',
          'verification_json', 'ready_at'))
      OR (table_name = 'ai_knowledge_base' AND column_name = 'active_index_version')
      OR (table_name = 'ai_document_version' AND column_name = 'redacted_structure_json')
  )
ORDER BY table_name, ordinal_position;

DROP PROCEDURE IF EXISTS `hmdp_report_ai_migration_preconditions`;
DELIMITER $$
CREATE PROCEDURE `hmdp_report_ai_migration_preconditions`()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_outbox_event'
          AND column_name = 'deduplication_key'
    ) THEN
        SELECT 'duplicate_deduplication_key' AS audit_type,
            deduplication_key AS key_part_1, NULL AS key_part_2, COUNT(*) AS duplicate_count
        FROM ai_outbox_event
        WHERE deduplication_key IS NOT NULL
        GROUP BY deduplication_key
        HAVING COUNT(*) > 1;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_outbox_dead_letter'
    ) THEN
        SELECT 'duplicate_dead_letter' AS audit_type,
            outbox_event_id AS key_part_1, consumer_name AS key_part_2,
            COUNT(*) AS duplicate_count
        FROM ai_outbox_dead_letter
        GROUP BY outbox_event_id, consumer_name
        HAVING COUNT(*) > 1;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
              AND table_name = 'ai_index_version'
    ) THEN
        SELECT 'duplicate_index_version_code' AS audit_type,
            code AS key_part_1, NULL AS key_part_2, COUNT(*) AS duplicate_count
        FROM ai_index_version
        GROUP BY code
        HAVING COUNT(*) > 1;

        SELECT id, tenant_id, workspace_id, knowledge_base_id, code,
            vector_index_name, lexical_index_name
        FROM ai_index_version
        WHERE vector_index_name NOT IN (
                  CONCAT('ai_kb_', code),
                  CONCAT('ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_')),
                  CONCAT('ai_kb_v2~', LEFT(SHA2(code, 256), 32))
              )
           OR lexical_index_name NOT IN (
                  CONCAT('ai_kb_', code),
                  CONCAT('ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_')),
                  CONCAT('ai_kb_v2~', LEFT(SHA2(code, 256), 32))
              )
        ORDER BY tenant_id, workspace_id, knowledge_base_id, code;

        SELECT physical_name, COUNT(DISTINCT id) AS owner_count
        FROM (
            SELECT id,
                CASE
                    WHEN vector_index_name = CONCAT('ai_kb_', code)
                      OR vector_index_name = CONCAT(
                          'ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_'))
                        THEN CONCAT('ai_kb_v2~', LEFT(SHA2(code, 256), 32))
                    ELSE vector_index_name
                END AS physical_name
            FROM ai_index_version
            UNION ALL
            SELECT id,
                CASE
                    WHEN lexical_index_name = CONCAT('ai_kb_', code)
                      OR lexical_index_name = CONCAT(
                          'ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_'))
                        THEN CONCAT('ai_kb_v2~', LEFT(SHA2(code, 256), 32))
                    ELSE lexical_index_name
                END AS physical_name
            FROM ai_index_version
        ) projected_names
        GROUP BY physical_name
        HAVING COUNT(DISTINCT id) > 1;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_knowledge_base_version'
    ) THEN
        SELECT 'duplicate_knowledge_version_code' AS audit_type,
            index_version AS key_part_1, NULL AS key_part_2, COUNT(*) AS duplicate_count
        FROM ai_knowledge_base_version
        GROUP BY index_version
        HAVING COUNT(*) > 1;
    END IF;
END$$
DELIMITER ;

CALL `hmdp_report_ai_migration_preconditions`();
DROP PROCEDURE IF EXISTS `hmdp_report_ai_migration_preconditions`;

SELECT table_name, index_name, non_unique, seq_in_index, column_name, collation
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND (
      (table_name = 'ai_outbox_event' AND index_name = 'uk_ai_outbox_deduplication')
      OR (table_name = 'ai_outbox_dead_letter'
          AND index_name = 'uk_ai_outbox_dead_letter_consumer')
  )
ORDER BY table_name, index_name, seq_in_index;
