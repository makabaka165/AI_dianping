package com.hmdp.ai.application.dto.prompt;

import com.hmdp.ai.domain.prompt.PromptDefinition;

import java.time.Instant;

public final class PromptResponse {
    private final String id;
    private final String code;
    private final String name;
    private final String description;
    private final int latestVersion;
    private final String status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public PromptResponse(PromptDefinition prompt) {
        this.id = prompt.getId();
        this.code = prompt.getCode();
        this.name = prompt.getName();
        this.description = prompt.getDescription();
        this.latestVersion = prompt.getLatestVersion();
        this.status = prompt.getStatus();
        this.createdAt = prompt.getCreatedAt();
        this.updatedAt = prompt.getUpdatedAt();
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
