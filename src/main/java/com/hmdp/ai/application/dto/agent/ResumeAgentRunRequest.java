package com.hmdp.ai.application.dto.agent;

import com.fasterxml.jackson.databind.JsonNode;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

public class ResumeAgentRunRequest {
    @NotBlank @Size(max = 256) private String resumeToken;
    @NotNull @Size(max = 64) private Map<@Size(max = 64) String, JsonNode> variables = new LinkedHashMap<>();

    public String getResumeToken() { return resumeToken; }
    public void setResumeToken(String resumeToken) { this.resumeToken = resumeToken; }
    public Map<String, JsonNode> getVariables() { return variables; }
    public void setVariables(Map<String, JsonNode> variables) { this.variables = variables; }
}
