package com.hmdp.ai.application.dto.prompt;

import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;

import java.time.Instant;

public final class PromptVersionResponse {
    private final String id;
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

    public PromptVersionResponse(PromptVersion version) {
        this.id = version.getId();
        this.promptId = version.getPromptId();
        this.version = version.getVersion();
        this.systemPrompt = version.getSystemPrompt();
        this.taskPrompt = version.getTaskPrompt();
        this.toolInstruction = version.getToolInstruction();
        this.retrievalInstruction = version.getRetrievalInstruction();
        this.outputInstruction = version.getOutputInstruction();
        this.variablesSchema = version.getVariablesSchema();
        this.inputSchema = version.getInputSchema();
        this.outputSchema = version.getOutputSchema();
        this.examplesJson = version.getExamplesJson();
        this.status = version.getStatus();
        this.contentHash = version.getContentHash();
        this.changeNote = version.getChangeNote();
        this.publishedAt = version.getPublishedAt();
        this.publishedBy = version.getPublishedBy();
        this.createdAt = version.getCreatedAt();
    }

    public String getId() { return id; }
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
