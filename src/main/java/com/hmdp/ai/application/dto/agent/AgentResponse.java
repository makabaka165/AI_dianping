package com.hmdp.ai.application.dto.agent;

import com.hmdp.ai.domain.agent.AgentDefinition;

import java.time.Instant;

public final class AgentResponse {
    private final String id;
    private final String code;
    private final String name;
    private final String description;
    private final int latestVersion;
    private final String status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public AgentResponse(AgentDefinition agent) {
        this.id = agent.getId();
        this.code = agent.getCode();
        this.name = agent.getName();
        this.description = agent.getDescription();
        this.latestVersion = agent.getLatestVersion();
        this.status = agent.getStatus();
        this.createdAt = agent.getCreatedAt();
        this.updatedAt = agent.getUpdatedAt();
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getLatestVersion() { return latestVersion; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
