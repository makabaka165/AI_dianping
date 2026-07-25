package com.hmdp.ai.application.dto.session;

public final class SessionScopeResponse {
    private final String tenantId;
    private final String workspaceId;

    public SessionScopeResponse(String tenantId, String workspaceId) {
        this.tenantId = tenantId;
        this.workspaceId = workspaceId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }
}
