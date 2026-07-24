-- Keep historical Flyway migrations immutable. This forward migration repairs schemas that were
-- baselined from an intermediate AI-platform snapshot and may be missing one of these objects.

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_index_version' AND column_name = 'build_mode'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE ai_index_version ADD COLUMN build_mode VARCHAR(32) NOT NULL DEFAULT ''SHADOW''',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_index_version' AND column_name = 'activated_at'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE ai_index_version ADD COLUMN activated_at TIMESTAMP(3) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_index_version' AND column_name = 'rollback_after'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE ai_index_version ADD COLUMN rollback_after TIMESTAMP(3) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_document_version'
      AND column_name = 'redacted_structure_json'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE ai_document_version ADD COLUMN redacted_structure_json LONGTEXT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_knowledge_base'
      AND column_name = 'active_index_version'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE ai_knowledge_base ADD COLUMN active_index_version VARCHAR(128) NULL AFTER latest_version',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_index_version'
      AND column_name = 'shadow_of_index_version'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE ai_index_version ADD COLUMN shadow_of_index_version VARCHAR(128) NULL AFTER build_mode',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_index_version'
      AND column_name = 'verification_json'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE ai_index_version ADD COLUMN verification_json LONGTEXT NULL AFTER chunk_count',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_index_version' AND column_name = 'ready_at'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE ai_index_version ADD COLUMN ready_at TIMESTAMP(3) NULL AFTER verification_json',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_outbox_event'
      AND column_name = 'deduplication_key'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE ai_outbox_event ADD COLUMN deduplication_key VARCHAR(255) NULL AFTER event_type',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_outbox_event'
      AND index_name = 'uk_ai_outbox_deduplication'
);
SET @ddl := IF(@index_exists = 0,
    'ALTER TABLE ai_outbox_event ADD UNIQUE KEY uk_ai_outbox_deduplication (deduplication_key)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_outbox_consumption'
      AND column_name = 'available_at'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE ai_outbox_consumption ADD COLUMN available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER failure_reason',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_outbox_dead_letter'
      AND index_name = 'uk_ai_outbox_dead_letter_consumer'
);
SET @ddl := IF(@index_exists = 0,
    'ALTER TABLE ai_outbox_dead_letter ADD UNIQUE KEY uk_ai_outbox_dead_letter_consumer (outbox_event_id, consumer_name)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE ai_knowledge_base kb
JOIN ai_index_version i ON i.tenant_id = kb.tenant_id
    AND i.workspace_id = kb.workspace_id
    AND i.knowledge_base_id = kb.id
    AND i.active = 1 AND i.status = 'READY' AND i.deleted = 0
SET kb.active_index_version = i.code
WHERE kb.active_index_version IS NULL;
