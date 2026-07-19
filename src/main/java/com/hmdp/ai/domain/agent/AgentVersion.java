package com.hmdp.ai.domain.agent;

import com.hmdp.ai.domain.prompt.VersionStatus;

import java.time.Instant;

public final class AgentVersion {
    private final String id;
    private final String tenantId;
    private final String workspaceId;
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

    public AgentVersion(String id, String tenantId, String workspaceId, String agentId, int version,
                        String name, String description, String modelProfileId, String promptVersionId,
                        String workflowVersionId, String memoryPolicyJson, String inputSchema,
                        String outputSchema, String executionPolicyJson, String responseRenderPolicyJson,
                        VersionStatus status, String contentHash, String changeNote, Instant publishedAt,
                        String publishedBy, Instant createdAt) {
        this(id, tenantId, workspaceId, agentId, version, name, description, modelProfileId, modelProfileId,
                promptVersionId, workflowVersionId, memoryPolicyJson, inputSchema, outputSchema,
                executionPolicyJson, responseRenderPolicyJson, status, contentHash, changeNote, publishedAt,
                publishedBy, createdAt);
    }

    public AgentVersion(String id, String tenantId, String workspaceId, String agentId, int version,
                        String name, String description, String modelProfileId, String modelProfileVersionId,
                        String promptVersionId, String workflowVersionId, String memoryPolicyJson, String inputSchema,
                        String outputSchema, String executionPolicyJson, String responseRenderPolicyJson,
                        VersionStatus status, String contentHash, String changeNote, Instant publishedAt,
                        String publishedBy, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.workspaceId = workspaceId;
        this.agentId = agentId;
        this.version = version;
        this.name = name;
        this.description = description;
        this.modelProfileId = modelProfileId;
        this.modelProfileVersionId = modelProfileVersionId;
        this.promptVersionId = promptVersionId;
        this.workflowVersionId = workflowVersionId;
        this.memoryPolicyJson = memoryPolicyJson;
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.executionPolicyJson = executionPolicyJson;
        this.responseRenderPolicyJson = responseRenderPolicyJson;
        this.status = status;
        this.contentHash = contentHash;
        this.changeNote = changeNote;
        this.publishedAt = publishedAt;
        this.publishedBy = publishedBy;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
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
