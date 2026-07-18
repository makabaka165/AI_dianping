package com.hmdp.ai.application.dto.workflow;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

public class CreateWorkflowVersionRequest {
    @NotBlank @Size(max = 200000) private String inputSchema;
    @NotBlank @Size(max = 200000) private String outputSchema;
    @NotBlank @Size(max = 200000) private String variablesSchema = "{\"type\":\"object\"}";
    @NotBlank @Size(max = 200000) private String executionPolicyJson = "{}";
    @NotBlank @Size(max = 1000) private String changeNote;
    @NotEmpty @Size(max = 256) private List<@Valid WorkflowNodeRequest> nodes = new ArrayList<>();
    @NotEmpty @Size(max = 1024) private List<@Valid WorkflowEdgeRequest> edges = new ArrayList<>();

    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }
    public String getOutputSchema() { return outputSchema; }
    public void setOutputSchema(String outputSchema) { this.outputSchema = outputSchema; }
    public String getVariablesSchema() { return variablesSchema; }
    public void setVariablesSchema(String variablesSchema) { this.variablesSchema = variablesSchema; }
    public String getExecutionPolicyJson() { return executionPolicyJson; }
    public void setExecutionPolicyJson(String executionPolicyJson) { this.executionPolicyJson = executionPolicyJson; }
    public String getChangeNote() { return changeNote; }
    public void setChangeNote(String changeNote) { this.changeNote = changeNote; }
    public List<WorkflowNodeRequest> getNodes() { return nodes; }
    public void setNodes(List<WorkflowNodeRequest> nodes) { this.nodes = nodes; }
    public List<WorkflowEdgeRequest> getEdges() { return edges; }
    public void setEdges(List<WorkflowEdgeRequest> edges) { this.edges = edges; }
}
