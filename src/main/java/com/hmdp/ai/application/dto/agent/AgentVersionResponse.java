package com.hmdp.ai.application.dto.agent;

import com.hmdp.ai.domain.agent.AgentVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;

import java.time.Instant;

public final class AgentVersionResponse {
    private final String id;
    private final String agentId;
    private final int version;
    private final String name;
    private final String description;
    private final String modelProfileId;
    private final String modelProfileVersionId;
    private final String promptVersionId;
    private final String workflowVersionId;
    private final String memoryPolicyJson;
    private final String inputSchema;
    private final String outputSchema;
    private final String executionPolicyJson;
    private final String responseRenderPolicyJson;
    private final VersionStatus status;
    private final String contentHash;
    private final String changeNote;
    private final Instant publishedAt;
    private final String publishedBy;
    private final Instant createdAt;

    public AgentVersionResponse(AgentVersion version) {
        this.id = version.getId();
        this.agentId = version.getAgentId();
        this.version = version.getVersion();
        this.name = version.getName();
        this.description = version.getDescription();
        this.modelProfileId = version.getModelProfileId();
        this.modelProfileVersionId = version.getModelProfileVersionId();
        this.promptVersionId = version.getPromptVersionId();
        this.workflowVersionId = version.getWorkflowVersionId();
        this.memoryPolicyJson = version.getMemoryPolicyJson();
        this.inputSchema = version.getInputSchema();
        this.outputSchema = version.getOutputSchema();
        this.executionPolicyJson = version.getExecutionPolicyJson();
        this.responseRenderPolicyJson = version.getResponseRenderPolicyJson();
        this.status = version.getStatus();
        this.contentHash = version.getContentHash();
        this.changeNote = version.getChangeNote();
        this.publishedAt = version.getPublishedAt();
        this.publishedBy = version.getPublishedBy();
        this.createdAt = version.getCreatedAt();
    }

    public String getId() { return id; }
    public String getAgentId() { return agentId; }
    public int getVersion() { return version; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getModelProfileId() { return modelProfileId; }
    public String getModelProfileVersionId() { return modelProfileVersionId; }
    public String getPromptVersionId() { return promptVersionId; }
    public String getWorkflowVersionId() { return workflowVersionId; }
    public String getMemoryPolicyJson() { return memoryPolicyJson; }
    public String getInputSchema() { return inputSchema; }
    public String getOutputSchema() { return outputSchema; }
    public String getExecutionPolicyJson() { return executionPolicyJson; }
    public String getResponseRenderPolicyJson() { return responseRenderPolicyJson; }
    public VersionStatus getStatus() { return status; }
    public String getContentHash() { return contentHash; }
    public String getChangeNote() { return changeNote; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getPublishedBy() { return publishedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
