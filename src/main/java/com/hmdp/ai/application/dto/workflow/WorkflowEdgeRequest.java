package com.hmdp.ai.application.dto.workflow;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class WorkflowEdgeRequest {
    @NotBlank @Size(max = 128) private String sourceNodeCode;
    @NotBlank @Size(max = 128) private String targetNodeCode;
    @Size(max = 200000) private String conditionJson;
    @Min(-10000) @Max(10000) private int priority;
    @Size(max = 128) private String label;

    public String getSourceNodeCode() { return sourceNodeCode; }
    public void setSourceNodeCode(String sourceNodeCode) { this.sourceNodeCode = sourceNodeCode; }
    public String getTargetNodeCode() { return targetNodeCode; }
    public void setTargetNodeCode(String targetNodeCode) { this.targetNodeCode = targetNodeCode; }
    public String getConditionJson() { return conditionJson; }
    public void setConditionJson(String conditionJson) { this.conditionJson = conditionJson; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
