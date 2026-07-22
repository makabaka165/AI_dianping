package com.hmdp.ai.application.dto.evaluation;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class EvaluationExecutionOptions {
    private boolean fakeModel;
    private boolean captureTrace = true;

    @Size(max = 64)
    private String agentId;

    @Min(1)
    private Integer agentVersion;

    @Size(max = 64)
    private String modelProfileVersionId;

    @Pattern(regexp = "TEXT|JSON")
    private String responseFormat = "JSON";

    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private Double temperature;

    @Min(1)
    private Integer maxOutputTokens;

    @Size(max = 8000)
    private String extraInstruction;

    public boolean isFakeModel() { return fakeModel; }
    public void setFakeModel(boolean fakeModel) { this.fakeModel = fakeModel; }
    public boolean isCaptureTrace() { return captureTrace; }
    public void setCaptureTrace(boolean captureTrace) { this.captureTrace = captureTrace; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public Integer getAgentVersion() { return agentVersion; }
    public void setAgentVersion(Integer agentVersion) { this.agentVersion = agentVersion; }
    public String getModelProfileVersionId() { return modelProfileVersionId; }
    public void setModelProfileVersionId(String modelProfileVersionId) {
        this.modelProfileVersionId = modelProfileVersionId;
    }
    public String getResponseFormat() { return responseFormat; }
    public void setResponseFormat(String responseFormat) { this.responseFormat = responseFormat; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public String getExtraInstruction() { return extraInstruction; }
    public void setExtraInstruction(String extraInstruction) { this.extraInstruction = extraInstruction; }
}
