package com.hmdp.ai.application.dto.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.RunStatus;

import java.time.Instant;

public final class AgentRunDetailResponse {
    private final String runId;
    private final String agentId;
    private final int agentVersion;
    private final RunStatus status;
    private final String sessionId;
    private final String conversationId;
    private final String traceId;
    private final JsonNode versionSnapshot;
    private final JsonNode output;
    private final String errorCode;
    private final String errorMessage;
    private final Instant queuedAt;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final Instant deadlineAt;

    public AgentRunDetailResponse(AgentRunRecord run, JsonNode versionSnapshot, JsonNode output) {
        this.runId = run.getId();
        this.agentId = run.getAgentId();
        this.agentVersion = run.getAgentVersion();
        this.status = run.getStatus();
        this.sessionId = run.getSessionId();
        this.conversationId = run.getConversationId();
        this.traceId = run.getTraceId();
        this.versionSnapshot = versionSnapshot;
        this.output = output;
        this.errorCode = run.getErrorCode();
        this.errorMessage = run.getErrorMessage();
        this.queuedAt = run.getQueuedAt();
        this.startedAt = run.getStartedAt();
        this.finishedAt = run.getFinishedAt();
        this.deadlineAt = run.getDeadlineAt();
    }

    public String getRunId() { return runId; }
    public String getAgentId() { return agentId; }
    public int getAgentVersion() { return agentVersion; }
    public RunStatus getStatus() { return status; }
    public String getSessionId() { return sessionId; }
    public String getConversationId() { return conversationId; }
    public String getTraceId() { return traceId; }
    public JsonNode getVersionSnapshot() { return versionSnapshot; }
    public JsonNode getOutput() { return output; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
}
