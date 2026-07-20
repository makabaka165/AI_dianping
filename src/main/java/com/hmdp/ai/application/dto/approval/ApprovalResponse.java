package com.hmdp.ai.application.dto.approval;

import java.time.Instant;

public final class ApprovalResponse {
    private final String id;
    private final String runId;
    private final String nodeRunId;
    private final String toolCallId;
    private final String toolId;
    private final int toolVersion;
    private final String riskLevel;
    private final String inputHash;
    private final String inputSummary;
    private final String requestedBy;
    private final String status;
    private final Instant expiresAt;
    private final Instant createdAt;

    public ApprovalResponse(String id, String runId, String nodeRunId, String toolCallId, String toolId,
                            int toolVersion, String riskLevel, String inputHash, String inputSummary,
                            String requestedBy, String status, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.nodeRunId = nodeRunId;
        this.toolCallId = toolCallId;
        this.toolId = toolId;
        this.toolVersion = toolVersion;
        this.riskLevel = riskLevel;
        this.inputHash = inputHash;
        this.inputSummary = inputSummary;
        this.requestedBy = requestedBy;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getNodeRunId() { return nodeRunId; }
    public String getToolCallId() { return toolCallId; }
    public String getToolId() { return toolId; }
    public int getToolVersion() { return toolVersion; }
    public String getRiskLevel() { return riskLevel; }
    public String getInputHash() { return inputHash; }
    public String getInputSummary() { return inputSummary; }
    public String getRequestedBy() { return requestedBy; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
