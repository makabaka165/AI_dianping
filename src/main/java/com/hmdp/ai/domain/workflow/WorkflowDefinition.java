package com.hmdp.ai.domain.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.Instant;

public final class WorkflowDefinition {
    private final String id;
    private final String tenantId;
    private final String workspaceId;
    private final String workflowId;
    private final int version;
    private final String inputSchema;
    private final String outputSchema;
    private final String variablesSchema;
    private final String executionPolicyJson;
    private final String status;
    private final List<WorkflowNodeDefinition> nodes;
    private final List<WorkflowEdgeDefinition> edges;
    private final String contentHash;
    private final String changeNote;
    private final Instant publishedAt;
    private final String publishedBy;

    public WorkflowDefinition(String id,String tenantId,String workspaceId,String workflowId,int version,
                              String inputSchema,String outputSchema,String variablesSchema,String executionPolicyJson,
                              String status,List<WorkflowNodeDefinition> nodes,List<WorkflowEdgeDefinition> edges){
        this(id, tenantId, workspaceId, workflowId, version, inputSchema, outputSchema, variablesSchema,
                executionPolicyJson, status, nodes, edges, null, null, null, null);
    }

    public WorkflowDefinition(String id,String tenantId,String workspaceId,String workflowId,int version,
                              String inputSchema,String outputSchema,String variablesSchema,String executionPolicyJson,
                              String status,List<WorkflowNodeDefinition> nodes,List<WorkflowEdgeDefinition> edges,
                              String contentHash,String changeNote,Instant publishedAt,String publishedBy){
        this.id=id;this.tenantId=tenantId;this.workspaceId=workspaceId;this.workflowId=workflowId;this.version=version;
        this.inputSchema=inputSchema;this.outputSchema=outputSchema;this.variablesSchema=variablesSchema;
        this.executionPolicyJson=executionPolicyJson;this.status=status;
        this.nodes=Collections.unmodifiableList(new ArrayList<>(nodes));
        this.edges=Collections.unmodifiableList(new ArrayList<>(edges));
        this.contentHash=contentHash;this.changeNote=changeNote;this.publishedAt=publishedAt;this.publishedBy=publishedBy;
    }
    public String getId(){return id;} public String getTenantId(){return tenantId;} public String getWorkspaceId(){return workspaceId;}
    public String getWorkflowId(){return workflowId;} public int getVersion(){return version;} public String getInputSchema(){return inputSchema;}
    public String getOutputSchema(){return outputSchema;} public String getVariablesSchema(){return variablesSchema;}
    public String getExecutionPolicyJson(){return executionPolicyJson;} public String getStatus(){return status;}
    public List<WorkflowNodeDefinition> getNodes(){return nodes;} public List<WorkflowEdgeDefinition> getEdges(){return edges;}
    public String getContentHash(){return contentHash;} public String getChangeNote(){return changeNote;}
    public Instant getPublishedAt(){return publishedAt;} public String getPublishedBy(){return publishedBy;}
}
