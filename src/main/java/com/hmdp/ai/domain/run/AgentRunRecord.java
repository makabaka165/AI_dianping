package com.hmdp.ai.domain.run;

import java.time.Instant;

public final class AgentRunRecord {
    private final String id;
    private final String tenantId;
    private final String workspaceId;
    private final String userId;
    private final String sessionId;
    private final String conversationId;
    private final String agentId;
    private final int agentVersion;
    private final RunStatus status;
    private final String responseMode;
    private final String inputJson;
    private final String outputJson;
    private final String metadataJson;
    private final String versionSnapshotJson;
    private final String budgetJson;
    private final String authorizationJson;
    private final String traceId;
    private final String retryOfRunId;
    private final int attempt;
    private final String errorCode;
    private final String errorMessage;
    private final Instant queuedAt;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final Instant deadlineAt;
    private final Instant createdAt;

    public AgentRunRecord(String id, String tenantId, String workspaceId, String userId, String sessionId,
                          String conversationId, String agentId, int agentVersion, RunStatus status,
                          String responseMode, String inputJson, String outputJson, String metadataJson,
                          String versionSnapshotJson, String budgetJson, String authorizationJson,
                          String traceId, String retryOfRunId,
                          int attempt, String errorCode, String errorMessage, Instant queuedAt, Instant startedAt,
                          Instant finishedAt, Instant deadlineAt, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.conversationId = conversationId;
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.status = status;
        this.responseMode = responseMode;
        this.inputJson = inputJson;
        this.outputJson = outputJson;
        this.metadataJson = metadataJson;
        this.versionSnapshotJson = versionSnapshotJson;
        this.budgetJson = budgetJson;
        this.authorizationJson = authorizationJson;
        this.traceId = traceId;
        this.retryOfRunId = retryOfRunId;
        this.attempt = attempt;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.queuedAt = queuedAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.deadlineAt = deadlineAt;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public String getConversationId() { return conversationId; }
    public String getAgentId() { return agentId; }
    public int getAgentVersion() { return agentVersion; }
    public RunStatus getStatus() { return status; }
    public String getResponseMode() { return responseMode; }
    public String getInputJson() { return inputJson; }
    public String getOutputJson() { return outputJson; }
    public String getMetadataJson() { return metadataJson; }
    public String getVersionSnapshotJson() { return versionSnapshotJson; }
    public String getBudgetJson() { return budgetJson; }
    public String getAuthorizationJson() { return authorizationJson; }
    public String getTraceId() { return traceId; }
    public String getRetryOfRunId() { return retryOfRunId; }
    public int getAttempt() { return attempt; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public Instant getCreatedAt() { return createdAt; }
}
