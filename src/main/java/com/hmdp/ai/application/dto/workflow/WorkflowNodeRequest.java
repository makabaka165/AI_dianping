package com.hmdp.ai.application.dto.workflow;

import com.hmdp.ai.domain.workflow.WorkflowNodeType;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class WorkflowNodeRequest {
    @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z][A-Za-z0-9._-]*") private String code;
    @NotNull private WorkflowNodeType type;
    @NotBlank @Size(max = 128) private String name;
    @NotBlank @Size(max = 200000) private String configurationJson = "{}";
    @NotBlank @Size(max = 200000) private String inputMappingJson = "{}";
    @NotBlank @Size(max = 200000) private String outputMappingJson = "{}";
    @Min(1) @Max(600000) private int timeoutMs = 30000;
    @Min(1) @Max(10) private int maxAttempts = 1;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public WorkflowNodeType getType() { return type; }
    public void setType(WorkflowNodeType type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getConfigurationJson() { return configurationJson; }
    public void setConfigurationJson(String configurationJson) { this.configurationJson = configurationJson; }
    public String getInputMappingJson() { return inputMappingJson; }
    public void setInputMappingJson(String inputMappingJson) { this.inputMappingJson = inputMappingJson; }
    public String getOutputMappingJson() { return outputMappingJson; }
    public void setOutputMappingJson(String outputMappingJson) { this.outputMappingJson = outputMappingJson; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}
