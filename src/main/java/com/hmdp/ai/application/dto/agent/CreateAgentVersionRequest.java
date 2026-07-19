package com.hmdp.ai.application.dto.agent;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

public class CreateAgentVersionRequest {
    @NotBlank @Size(max = 128) private String name;
    @Size(max = 1000) private String description;
    @Size(max = 64) private String modelProfileId;
    @Size(max = 64) private String modelProfileVersionId;
    @NotBlank @Size(max = 64) private String promptVersionId;
    @NotBlank @Size(max = 64) private String workflowVersionId;
    @NotBlank @Size(max = 16000) private String memoryPolicyJson;
    @NotBlank @Size(max = 50000) private String inputSchema;
    @NotBlank @Size(max = 50000) private String outputSchema;
    @NotBlank @Size(max = 16000) private String executionPolicyJson;
    @NotBlank @Size(max = 16000) private String responseRenderPolicyJson;
    @NotNull @Size(max = 64) private List<@NotBlank @Size(max = 64) String> toolVersionIds = new ArrayList<>();
    @NotNull @Size(max = 64) private List<@NotBlank @Size(max = 64) String> knowledgeBaseVersionIds = new ArrayList<>();
    @NotBlank @Size(max = 1000) private String changeNote;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getModelProfileId() { return modelProfileId; }
    public void setModelProfileId(String modelProfileId) { this.modelProfileId = modelProfileId; }
    public String getModelProfileVersionId() { return modelProfileVersionId; }
    public void setModelProfileVersionId(String modelProfileVersionId) { this.modelProfileVersionId = modelProfileVersionId; }
    public String getPromptVersionId() { return promptVersionId; }
    public void setPromptVersionId(String promptVersionId) { this.promptVersionId = promptVersionId; }
    public String getWorkflowVersionId() { return workflowVersionId; }
    public void setWorkflowVersionId(String workflowVersionId) { this.workflowVersionId = workflowVersionId; }
    public String getMemoryPolicyJson() { return memoryPolicyJson; }
    public void setMemoryPolicyJson(String memoryPolicyJson) { this.memoryPolicyJson = memoryPolicyJson; }
    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }
    public String getOutputSchema() { return outputSchema; }
    public void setOutputSchema(String outputSchema) { this.outputSchema = outputSchema; }
    public String getExecutionPolicyJson() { return executionPolicyJson; }
    public void setExecutionPolicyJson(String executionPolicyJson) { this.executionPolicyJson = executionPolicyJson; }
    public String getResponseRenderPolicyJson() { return responseRenderPolicyJson; }
    public void setResponseRenderPolicyJson(String responseRenderPolicyJson) { this.responseRenderPolicyJson = responseRenderPolicyJson; }
    public List<String> getToolVersionIds() { return toolVersionIds; }
    public void setToolVersionIds(List<String> toolVersionIds) { this.toolVersionIds = toolVersionIds; }
    public List<String> getKnowledgeBaseVersionIds() { return knowledgeBaseVersionIds; }
    public void setKnowledgeBaseVersionIds(List<String> knowledgeBaseVersionIds) { this.knowledgeBaseVersionIds = knowledgeBaseVersionIds; }
    public String getChangeNote() { return changeNote; }
    public void setChangeNote(String changeNote) { this.changeNote = changeNote; }
}
