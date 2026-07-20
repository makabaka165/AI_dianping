ALTER TABLE ai_knowledge_base
    ADD COLUMN IF NOT EXISTS active_index_version VARCHAR(128) NULL AFTER latest_version;

ALTER TABLE ai_index_version
    ADD COLUMN IF NOT EXISTS shadow_of_index_version VARCHAR(128) NULL AFTER build_mode,
    ADD COLUMN IF NOT EXISTS verification_json LONGTEXT NULL AFTER chunk_count,
    ADD COLUMN IF NOT EXISTS ready_at TIMESTAMP(3) NULL AFTER verification_json;

ALTER TABLE ai_outbox_event
    ADD COLUMN IF NOT EXISTS deduplication_key VARCHAR(255) NULL AFTER event_type,
    ADD UNIQUE KEY uk_ai_outbox_deduplication (deduplication_key);

ALTER TABLE ai_outbox_consumption
    ADD COLUMN IF NOT EXISTS available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER failure_reason;

ALTER TABLE ai_outbox_dead_letter
    ADD UNIQUE KEY uk_ai_outbox_dead_letter_consumer (outbox_event_id, consumer_name);

UPDATE ai_knowledge_base kb
JOIN ai_index_version i ON i.tenant_id=kb.tenant_id
    AND i.workspace_id=kb.workspace_id
    AND i.knowledge_base_id=kb.id
    AND i.active=1 AND i.status='READY' AND i.deleted=0
SET kb.active_index_version=i.code
WHERE kb.active_index_version IS NULL;
