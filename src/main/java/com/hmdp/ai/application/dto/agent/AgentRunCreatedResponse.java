package com.hmdp.ai.application.dto.agent;

import com.hmdp.ai.domain.run.RunStatus;

public final class AgentRunCreatedResponse {
    private final String runId;
    private final RunStatus status;
    private final String agentId;
    private final int agentVersion;

    public AgentRunCreatedResponse(String runId, RunStatus status, String agentId, int agentVersion) {
        this.runId = runId;
        this.status = status;
        this.agentId = agentId;
        this.agentVersion = agentVersion;
    }

    public String getRunId() { return runId; }
    public RunStatus getStatus() { return status; }
    public String getAgentId() { return agentId; }
    public int getAgentVersion() { return agentVersion; }
}
