CREATE TABLE IF NOT EXISTS ai_tenant (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL DEFAULT '',
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_tenant_code (code),
    KEY idx_ai_tenant_status (status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_tenant_member (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL DEFAULT '',
    user_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_tenant_member (tenant_id, user_id),
    KEY idx_ai_tenant_member_user (user_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_workspace (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_workspace_tenant_code (tenant_id, code),
    UNIQUE KEY uk_ai_workspace_scope (tenant_id, workspace_id),
    KEY idx_ai_workspace_status (tenant_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_workspace_member (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_workspace_member (workspace_id, user_id),
    KEY idx_ai_workspace_member_user (tenant_id, user_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_resource_acl (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    principal_type VARCHAR(32) NOT NULL,
    principal_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_resource_acl (tenant_id, workspace_id, resource_type, resource_id, principal_type, principal_id),
    KEY idx_ai_resource_acl_lookup (tenant_id, workspace_id, resource_type, resource_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO ai_tenant (id, tenant_id, workspace_id, code, name, status, created_by, updated_by)
VALUES ('default', 'default', '', 'default', 'Default tenant', 'ACTIVE', 'system', 'system');
INSERT IGNORE INTO ai_workspace (id, tenant_id, workspace_id, code, name, status, created_by, updated_by)
VALUES ('default', 'default', 'default', 'default', 'Default workspace', 'ACTIVE', 'system', 'system');
INSERT IGNORE INTO ai_tenant_member (id, tenant_id, workspace_id, user_id, role, status, created_by, updated_by)
VALUES ('default-owner', 'default', '', '1', 'OWNER', 'ACTIVE', 'system', 'system');
INSERT IGNORE INTO ai_workspace_member (id, tenant_id, workspace_id, user_id, role, status, created_by, updated_by)
VALUES ('default-owner', 'default', 'default', '1', 'OWNER', 'ACTIVE', 'system', 'system');
