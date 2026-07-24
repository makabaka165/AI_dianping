-- MySQL 8 compatibility replacement for the historical migration with the same version.
-- This file is intentionally outside db/migration. It is executed only by the explicit
-- mysql8 Flyway bridge, then the original migration checksum is recorded in the main history.

CREATE TABLE IF NOT EXISTS ai_approval_request (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    node_run_id VARCHAR(64) NOT NULL,
    tool_call_id VARCHAR(64) NOT NULL,
    tool_id VARCHAR(64) NOT NULL,
    tool_version INT NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    input_summary VARCHAR(1000) NOT NULL,
    requested_by VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    KEY idx_ai_approval_pending (tenant_id, workspace_id, status, expires_at, deleted),
    KEY idx_ai_approval_tool_call (tool_call_id, input_hash, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_approval_decision (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    approval_request_id VARCHAR(64) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reason VARCHAR(1000) NULL,
    decided_by VARCHAR(64) NOT NULL,
    decided_at TIMESTAMP(3) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_approval_decision (approval_request_id),
    KEY idx_ai_approval_decider (tenant_id, workspace_id, decided_by, decided_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_outbox_consumption (
    id VARCHAR(64) PRIMARY KEY,
    outbox_event_id VARCHAR(64) NOT NULL,
    consumer_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    failure_reason VARCHAR(1000) NULL,
    claimed_at TIMESTAMP(3) NULL,
    claimed_by VARCHAR(128) NULL,
    lease_until TIMESTAMP(3) NULL,
    consumed_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_ai_outbox_consumer (outbox_event_id, consumer_name),
    KEY idx_ai_outbox_claim (consumer_name, status, lease_until, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_outbox_dead_letter (
    id VARCHAR(64) PRIMARY KEY,
    outbox_event_id VARCHAR(64) NOT NULL,
    consumer_name VARCHAR(128) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    failure_reason VARCHAR(1000) NOT NULL,
    attempt INT NOT NULL,
    replay_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    replayed_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_ai_outbox_dead_letter (consumer_name, replay_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- MySQL 8 does not support the historical conditional-column ALTER syntax.
-- Use metadata-guarded dynamic DDL instead.
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
