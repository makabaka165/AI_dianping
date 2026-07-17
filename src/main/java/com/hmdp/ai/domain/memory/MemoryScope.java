package com.hmdp.ai.domain.memory;

import java.util.Objects;

public final class MemoryScope {
    private final String tenantId;
    private final String workspaceId;
    private final String agentId;
    private final String userId;
    private final String sessionId;
    private final MemoryType memoryType;
    private final String resourceId;

    public MemoryScope(String tenantId, String workspaceId, String agentId, String userId,
                       String sessionId, MemoryType memoryType, String resourceId) {
        this.tenantId = required(tenantId, "tenantId");
        this.workspaceId = required(workspaceId, "workspaceId");
        this.agentId = required(agentId, "agentId");
        this.userId = required(userId, "userId");
        this.sessionId = required(sessionId, "sessionId");
        this.memoryType = Objects.requireNonNull(memoryType, "memoryType");
        this.resourceId = resourceId == null ? "" : resourceId;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getAgentId() { return agentId; }
    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public MemoryType getMemoryType() { return memoryType; }
    public String getResourceId() { return resourceId; }
}
