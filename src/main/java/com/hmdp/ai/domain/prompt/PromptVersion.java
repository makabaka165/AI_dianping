package com.hmdp.ai.domain.prompt;

import java.time.Instant;

public final class PromptVersion {
    private final String id;
    private final String tenantId;
    private final String workspaceId;
    private final String promptId;
    private final int version;
    private final String systemPrompt;
    private final String taskPrompt;
    private final String toolInstruction;
    private final String retrievalInstruction;
    private final String outputInstruction;
    private final String variablesSchema;
    private final String inputSchema;
    private final String outputSchema;
    private final String examplesJson;
    private final VersionStatus status;
    private final String contentHash;
    private final String changeNote;
    private final Instant publishedAt;
    private final String publishedBy;
    private final Instant createdAt;

    public PromptVersion(String id, String tenantId, String workspaceId, String promptId, int version,
                         String systemPrompt, String taskPrompt, String toolInstruction,
                         String retrievalInstruction, String outputInstruction, String variablesSchema,
                         String inputSchema, String outputSchema, String examplesJson, VersionStatus status,
                         String contentHash, String changeNote, Instant publishedAt, String publishedBy,
                         Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.workspaceId = workspaceId;
        this.promptId = promptId;
        this.version = version;
        this.systemPrompt = systemPrompt;
        this.taskPrompt = taskPrompt;
        this.toolInstruction = toolInstruction;
        this.retrievalInstruction = retrievalInstruction;
        this.outputInstruction = outputInstruction;
        this.variablesSchema = variablesSchema;
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.examplesJson = examplesJson;
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
    public String getPromptId() { return promptId; }
    public int getVersion() { return version; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getTaskPrompt() { return taskPrompt; }
    public String getToolInstruction() { return toolInstruction; }
    public String getRetrievalInstruction() { return retrievalInstruction; }
    public String getOutputInstruction() { return outputInstruction; }
    public String getVariablesSchema() { return variablesSchema; }
    public String getInputSchema() { return inputSchema; }
    public String getOutputSchema() { return outputSchema; }
    public String getExamplesJson() { return examplesJson; }
    public VersionStatus getStatus() { return status; }
    public String getContentHash() { return contentHash; }
    public String getChangeNote() { return changeNote; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getPublishedBy() { return publishedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
