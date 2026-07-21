package com.hmdp.ai.runtime.node;

import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.runtime.cancellation.NodeCancellationToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class NodeExecutionContext {
    private final ExecutionContext executionContext;
    private final PublishedAgentDefinition agent;
    private final WorkflowDefinition workflow;
    private final WorkflowNodeDefinition node;
    private final Map<String, Object> variables;
    private final List<WorkflowEdgeDefinition> outgoingEdges;
    private final String nodeRunId;
    private final NodeCancellationToken cancellationToken;

    public NodeExecutionContext(ExecutionContext executionContext, PublishedAgentDefinition agent,
                                WorkflowDefinition workflow, WorkflowNodeDefinition node,
                                Map<String, Object> variables, List<WorkflowEdgeDefinition> outgoingEdges) {
        this(executionContext, agent, workflow, node, variables, outgoingEdges, null);
    }

    public NodeExecutionContext(ExecutionContext executionContext, PublishedAgentDefinition agent,
                                WorkflowDefinition workflow, WorkflowNodeDefinition node,
                                Map<String, Object> variables, List<WorkflowEdgeDefinition> outgoingEdges,
                                String nodeRunId) {
        this(executionContext, agent, workflow, node, variables, outgoingEdges, nodeRunId,
                executionContext == null ? null : new NodeCancellationToken(null, executionContext.getDeadline()));
    }

    public NodeExecutionContext(ExecutionContext executionContext, PublishedAgentDefinition agent,
                                WorkflowDefinition workflow, WorkflowNodeDefinition node,
                                Map<String, Object> variables, List<WorkflowEdgeDefinition> outgoingEdges,
                                String nodeRunId, NodeCancellationToken cancellationToken) {
        this.executionContext = executionContext;
        this.agent = agent;
        this.workflow = workflow;
        this.node = node;
        this.variables = variables;
        this.outgoingEdges = Collections.unmodifiableList(new ArrayList<>(outgoingEdges));
        this.nodeRunId = nodeRunId;
        this.cancellationToken = cancellationToken;
    }

    public ExecutionContext getExecutionContext() { return executionContext; }
    public PublishedAgentDefinition getAgent() { return agent; }
    public WorkflowDefinition getWorkflow() { return workflow; }
    public WorkflowNodeDefinition getNode() { return node; }
    public Map<String, Object> getVariables() { return variables; }
    public List<WorkflowEdgeDefinition> getOutgoingEdges() { return outgoingEdges; }
    public String getNodeRunId() { return nodeRunId; }
    public NodeCancellationToken getCancellationToken() { return cancellationToken; }
}
