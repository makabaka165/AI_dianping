package com.hmdp.ai.application.dto.evaluation;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class CreateEvaluationRunRequest {
    @NotBlank
    @Size(max = 64)
    private String datasetId;

    @NotBlank
    @Size(max = 32)
    @Pattern(regexp = "AGENT|WORKFLOW|PROMPT|RAG|TOOL")
    private String targetType;

    @NotBlank
    @Size(max = 64)
    private String targetId;

    @Min(1)
    private Integer targetVersion;

    @Valid
    private EvaluationExecutionOptions executionOptions = new EvaluationExecutionOptions();

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public Integer getTargetVersion() { return targetVersion; }
    public void setTargetVersion(Integer targetVersion) { this.targetVersion = targetVersion; }
    public EvaluationExecutionOptions getExecutionOptions() { return executionOptions; }
    public void setExecutionOptions(EvaluationExecutionOptions executionOptions) {
        this.executionOptions = executionOptions == null ? new EvaluationExecutionOptions() : executionOptions;
    }
}
