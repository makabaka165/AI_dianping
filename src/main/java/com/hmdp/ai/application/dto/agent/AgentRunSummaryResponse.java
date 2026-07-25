package com.hmdp.ai.application.dto.agent;

import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.RunStatus;

import java.time.Instant;

public final class AgentRunSummaryResponse {
    private final String runId;
    private final String agentId;
    private final int agentVersion;
    private final RunStatus status;
    private final String sessionId;
    private final String errorCode;
    private final String errorMessage;
    private final Instant queuedAt;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final Instant createdAt;

    public AgentRunSummaryResponse(AgentRunRecord run) {
        this.runId = run.getId();
        this.agentId = run.getAgentId();
        this.agentVersion = run.getAgentVersion();
        this.status = run.getStatus();
        this.sessionId = run.getSessionId();
        this.errorCode = run.getErrorCode();
        this.errorMessage = run.getErrorMessage();
        this.queuedAt = run.getQueuedAt();
        this.startedAt = run.getStartedAt();
        this.finishedAt = run.getFinishedAt();
        this.createdAt = run.getCreatedAt();
    }

    public String getRunId() {
        return runId;
    }

    public String getAgentId() {
        return agentId;
    }

    public int getAgentVersion() {
        return agentVersion;
    }

    public RunStatus getStatus() {
        return status;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
