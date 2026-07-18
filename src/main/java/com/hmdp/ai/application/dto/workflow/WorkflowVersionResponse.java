package com.hmdp.ai.application.dto.workflow;

import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;

import java.time.Instant;
import java.util.List;

public final class WorkflowVersionResponse {
    private final String id; private final String workflowId; private final int version;
    private final String inputSchema; private final String outputSchema; private final String variablesSchema;
    private final String executionPolicyJson; private final String status; private final String contentHash;
    private final String changeNote; private final Instant publishedAt; private final String publishedBy;
    private final List<WorkflowNodeDefinition> nodes; private final List<WorkflowEdgeDefinition> edges;
    public WorkflowVersionResponse(WorkflowDefinition value){id=value.getId();workflowId=value.getWorkflowId();version=value.getVersion();inputSchema=value.getInputSchema();outputSchema=value.getOutputSchema();variablesSchema=value.getVariablesSchema();executionPolicyJson=value.getExecutionPolicyJson();status=value.getStatus();contentHash=value.getContentHash();changeNote=value.getChangeNote();publishedAt=value.getPublishedAt();publishedBy=value.getPublishedBy();nodes=value.getNodes();edges=value.getEdges();}
    public String getId(){return id;} public String getWorkflowId(){return workflowId;} public int getVersion(){return version;}
    public String getInputSchema(){return inputSchema;} public String getOutputSchema(){return outputSchema;}
    public String getVariablesSchema(){return variablesSchema;} public String getExecutionPolicyJson(){return executionPolicyJson;}
    public String getStatus(){return status;} public String getContentHash(){return contentHash;} public String getChangeNote(){return changeNote;}
    public Instant getPublishedAt(){return publishedAt;} public String getPublishedBy(){return publishedBy;}
    public List<WorkflowNodeDefinition> getNodes(){return nodes;} public List<WorkflowEdgeDefinition> getEdges(){return edges;}
}
