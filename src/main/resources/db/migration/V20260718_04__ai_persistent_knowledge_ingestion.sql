CREATE TABLE IF NOT EXISTS ai_document (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    code VARCHAR(128) NOT NULL,
    title VARCHAR(512) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    current_version INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_document_code (tenant_id, workspace_id, knowledge_base_id, code),
    KEY idx_ai_document_list (tenant_id, workspace_id, knowledge_base_id, status, deleted, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_document_version (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    bucket VARCHAR(128) NOT NULL,
    original_file_name VARCHAR(512) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    parsed_title VARCHAR(512) NULL,
    plain_text MEDIUMTEXT NULL,
    structure_json LONGTEXT NULL,
    parse_warnings_json LONGTEXT NULL,
    quality_score DOUBLE NOT NULL DEFAULT 0,
    quality_json LONGTEXT NULL,
    status VARCHAR(32) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    change_note VARCHAR(1000) NOT NULL,
    published_at TIMESTAMP(3) NULL,
    published_by VARCHAR(64) NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_document_version (document_id, version),
    UNIQUE KEY uk_ai_document_sha (tenant_id, knowledge_base_id, sha256, deleted),
    KEY idx_ai_document_version_status (tenant_id, workspace_id, knowledge_base_id, status, deleted),
    KEY idx_ai_document_version_document (document_id, status, deleted, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_document_chunk (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    knowledge_base_version INT NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    document_version INT NOT NULL,
    document_version_id VARCHAR(64) NOT NULL,
    index_version VARCHAR(128) NOT NULL,
    ordinal_no INT NOT NULL,
    parent_chunk_id VARCHAR(64) NULL,
    content MEDIUMTEXT NOT NULL,
    search_text MEDIUMTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    embedding_dimension INT NOT NULL,
    embedding MEDIUMBLOB NULL,
    quality_score DOUBLE NOT NULL DEFAULT 0,
    source_type VARCHAR(64) NOT NULL,
    page_no INT NULL,
    section_name VARCHAR(512) NULL,
    heading_path VARCHAR(1000) NULL,
    sheet_name VARCHAR(255) NULL,
    row_start INT NULL,
    row_end INT NULL,
    column_start INT NULL,
    column_end INT NULL,
    source_offset_start INT NULL,
    source_offset_end INT NULL,
    metadata_json LONGTEXT NOT NULL,
    effective_at TIMESTAMP(3) NULL,
    expired_at TIMESTAMP(3) NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_document_chunk_ordinal (document_version_id, index_version, ordinal_no),
    KEY idx_ai_chunk_acl (tenant_id, workspace_id, knowledge_base_id, index_version, status, deleted),
    KEY idx_ai_chunk_document (document_id, document_version, status, deleted),
    KEY idx_ai_chunk_time (effective_at, expired_at, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_ingestion_job (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    knowledge_base_version_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    document_version_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    attempt INT NOT NULL DEFAULT 0,
    error_code VARCHAR(128) NULL,
    error_message VARCHAR(1000) NULL,
    statistics_json LONGTEXT NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    KEY idx_ai_ingestion_document_version (document_version_id, knowledge_base_version_id, status, deleted),
    KEY idx_ai_ingestion_worker (status, updated_at, attempt, deleted),
    KEY idx_ai_ingestion_scope (tenant_id, workspace_id, knowledge_base_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_index_version (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    knowledge_base_version INT NOT NULL,
    code VARCHAR(128) NOT NULL,
    embedding_model_profile_id VARCHAR(64) NOT NULL,
    embedding_dimension INT NOT NULL,
    vector_index_name VARCHAR(128) NOT NULL,
    lexical_index_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 0,
    document_count BIGINT NOT NULL DEFAULT 0,
    chunk_count BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_index_version (tenant_id, workspace_id, knowledge_base_id, code),
    KEY idx_ai_index_active (tenant_id, workspace_id, knowledge_base_id, active, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_document_acl (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    principal_type VARCHAR(32) NOT NULL,
    principal_id VARCHAR(64) NOT NULL,
    permission VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_document_acl (document_id, principal_type, principal_id, permission),
    KEY idx_ai_document_acl_principal (tenant_id, workspace_id, principal_type, principal_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_outbox_event (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(3) NOT NULL,
    published_at TIMESTAMP(3) NULL,
    error_message VARCHAR(1000) NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    KEY idx_ai_outbox_publish (status, available_at, attempt, deleted),
    KEY idx_ai_outbox_aggregate (tenant_id, workspace_id, aggregate_type, aggregate_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Durable consumers are defined by the hardening migration as ai_outbox_consumption and
-- ai_outbox_dead_letter. Index builds use SHADOW index versions until verification succeeds.

INSERT IGNORE INTO ai_model_profile (
    id,tenant_id,workspace_id,code,name,provider,model_name,base_url,secret_ref,model_type,
    capabilities_json,default_parameters_json,context_window,max_output_tokens,timeout_ms,retry_policy_json,
    fallback_model_profile_id,input_token_price,output_token_price,enabled,status,created_by,updated_by
) VALUES (
    'model-shop-embedding','default','default','shop-embedding-default','Default shop embedding model',
    'OPENAI_COMPATIBLE','text-embedding-v3','https://dashscope.aliyuncs.com/compatible-mode/v1',
    'env:AI_EMBEDDING_API_KEY','EMBEDDING',
    '{"streaming":false,"toolCalling":false,"jsonSchema":false,"vision":false,"longContext":false}',
    '{}',8192,1,30000,'{"maxAttempts":2}',NULL,0,0,1,'ACTIVE','system','system'
);

UPDATE ai_knowledge_base_version
SET embedding_model_profile_id='model-shop-embedding'
WHERE id='kb-shop-enterprise-v1' AND embedding_model_profile_id='model-shop-chat';

INSERT IGNORE INTO ai_index_version (
    id,tenant_id,workspace_id,knowledge_base_id,knowledge_base_version,code,embedding_model_profile_id,
    embedding_dimension,vector_index_name,lexical_index_name,status,active,created_by,updated_by
) VALUES (
    'index-shop-enterprise-v1','default','default','kb-shop-enterprise',1,'shop-enterprise-v1',
    'model-shop-embedding',1024,'ai_kb_shop_enterprise_v1','ai_kb_shop_enterprise_v1','READY',1,'system','system'
);
