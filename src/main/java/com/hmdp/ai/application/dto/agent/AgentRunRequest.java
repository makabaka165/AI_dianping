package com.hmdp.ai.application.dto.agent;

import com.fasterxml.jackson.databind.JsonNode;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

public class AgentRunRequest {
    @NotBlank @Size(max = 64) private String agentId;
    @Min(1) @Max(1000000) private int agentVersion;
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") private String sessionId;
    @Valid @NotNull private AgentInputRequest input;
    @NotNull private AgentResponseMode responseMode = AgentResponseMode.BLOCKING;
    @NotNull @Size(max = 32) private Map<@Size(max = 64) String, JsonNode> metadata = new LinkedHashMap<>();

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public int getAgentVersion() { return agentVersion; }
    public void setAgentVersion(int agentVersion) { this.agentVersion = agentVersion; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public AgentInputRequest getInput() { return input; }
    public void setInput(AgentInputRequest input) { this.input = input; }
    public AgentResponseMode getResponseMode() { return responseMode; }
    public void setResponseMode(AgentResponseMode responseMode) { this.responseMode = responseMode; }
    public Map<String, JsonNode> getMetadata() { return metadata; }
    public void setMetadata(Map<String, JsonNode> metadata) { this.metadata = metadata; }
}
