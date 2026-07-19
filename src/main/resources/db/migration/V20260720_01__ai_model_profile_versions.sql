CREATE TABLE IF NOT EXISTS ai_model_profile_version (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    model_profile_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    secret_ref VARCHAR(255) NOT NULL,
    model_type VARCHAR(32) NOT NULL,
    capabilities_json LONGTEXT NOT NULL,
    default_parameters_json LONGTEXT NOT NULL,
    context_window INT NOT NULL,
    max_output_tokens INT NOT NULL,
    timeout_ms INT NOT NULL,
    retry_policy_json LONGTEXT NOT NULL,
    fallback_model_profile_version_id VARCHAR(64) NULL,
    input_token_price DECIMAL(18,8) NOT NULL DEFAULT 0,
    output_token_price DECIMAL(18,8) NOT NULL DEFAULT 0,
    content_hash CHAR(64) NOT NULL,
    change_note VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    published_at TIMESTAMP(3) NULL,
    published_by VARCHAR(64) NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_model_profile_version (model_profile_id, version),
    KEY idx_ai_model_profile_version_scope (tenant_id, workspace_id, model_profile_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE ai_agent_version ADD COLUMN model_profile_version_id VARCHAR(64) NULL AFTER model_profile_id;
ALTER TABLE ai_model_call ADD COLUMN model_profile_version_id VARCHAR(64) NULL AFTER model_profile_id;
ALTER TABLE ai_model_call ADD COLUMN invocation_id VARCHAR(64) NULL AFTER node_run_id;
ALTER TABLE ai_model_call ADD COLUMN estimated_usage TINYINT(1) NOT NULL DEFAULT 0 AFTER output_tokens;
ALTER TABLE ai_model_call ADD COLUMN estimated_cost DECIMAL(18,8) NOT NULL DEFAULT 0 AFTER estimated_usage;

INSERT IGNORE INTO ai_model_profile_version (
    id, tenant_id, workspace_id, model_profile_id, version, provider, model_name, base_url, secret_ref,
    model_type, capabilities_json, default_parameters_json, context_window, max_output_tokens, timeout_ms,
    retry_policy_json, input_token_price, output_token_price, content_hash, change_note, status,
    published_at, published_by, created_by, updated_by
)
SELECT 'model-shop-chat-v1', tenant_id, workspace_id, id, 1, provider, model_name, base_url, secret_ref,
       model_type, capabilities_json, default_parameters_json, context_window, max_output_tokens, timeout_ms,
       retry_policy_json, input_token_price, output_token_price,
       SHA2(CONCAT(id, ':1:', model_name, ':', base_url), 256),
       'Migrated from the logical model profile.', 'PUBLISHED', created_at, created_by, created_by, updated_by
FROM ai_model_profile
WHERE id = 'model-shop-chat';

UPDATE ai_agent_version
SET model_profile_version_id = 'model-shop-chat-v1'
WHERE model_profile_id = 'model-shop-chat' AND model_profile_version_id IS NULL;
