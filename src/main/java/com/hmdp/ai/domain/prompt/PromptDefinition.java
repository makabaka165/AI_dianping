package com.hmdp.ai.domain.prompt;

import java.time.Instant;

public final class PromptDefinition {
    private final String id;
    private final String tenantId;
    private final String workspaceId;
    private final String code;
    private final String name;
    private final String description;
    private final int latestVersion;
    private final String status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public PromptDefinition(String id, String tenantId, String workspaceId, String code, String name,
                            String description, int latestVersion, String status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.workspaceId = workspaceId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.latestVersion = latestVersion;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getLatestVersion() { return latestVersion; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
