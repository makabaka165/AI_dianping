package com.hmdp.ai.domain.security;

public final class WorkspaceContext {
    private final String workspaceId;
    public WorkspaceContext(String workspaceId) {
        if (workspaceId == null || workspaceId.trim().isEmpty()) throw new IllegalArgumentException("workspaceId required");
        this.workspaceId = workspaceId.trim();
    }
    public String getWorkspaceId() { return workspaceId; }
}
