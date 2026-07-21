package com.hmdp.ai.application.dto.agent;

import com.hmdp.ai.domain.run.RunStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;

public final class AgentRunCreatedResponse {
    private final String runId;
    private final RunStatus status;
    private final String agentDefinitionId;
    private final String agentCode;
    private final int agentVersion;

    public AgentRunCreatedResponse(String runId, RunStatus status, String agentDefinitionId,
                                   String agentCode, int agentVersion) {
        this.runId = runId;
        this.status = status;
        this.agentDefinitionId = agentDefinitionId;
        this.agentCode = agentCode;
        this.agentVersion = agentVersion;
    }

    /** Backward-compatible constructor for callers that only have the definition id. */
    public AgentRunCreatedResponse(String runId, RunStatus status, String agentDefinitionId, int agentVersion) {
        this(runId, status, agentDefinitionId, agentDefinitionId, agentVersion);
    }

    public String getRunId() { return runId; }
    public RunStatus getStatus() { return status; }
    public String getAgentDefinitionId() { return agentDefinitionId; }
    public String getAgentCode() { return agentCode; }
    @JsonIgnore
    public String getAgentId() { return agentDefinitionId; }
    public int getAgentVersion() { return agentVersion; }
}
