package com.hmdp.ai.runtime.node;

import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;

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

    public NodeExecutionContext(ExecutionContext executionContext, PublishedAgentDefinition agent,
                                WorkflowDefinition workflow, WorkflowNodeDefinition node,
                                Map<String, Object> variables, List<WorkflowEdgeDefinition> outgoingEdges) {
        this(executionContext, agent, workflow, node, variables, outgoingEdges, null);
    }

    public NodeExecutionContext(ExecutionContext executionContext, PublishedAgentDefinition agent,
                                WorkflowDefinition workflow, WorkflowNodeDefinition node,
                                Map<String, Object> variables, List<WorkflowEdgeDefinition> outgoingEdges,
                                String nodeRunId) {
        this.executionContext = executionContext;
        this.agent = agent;
        this.workflow = workflow;
        this.node = node;
        this.variables = variables;
        this.outgoingEdges = Collections.unmodifiableList(new ArrayList<>(outgoingEdges));
        this.nodeRunId = nodeRunId;
    }

    public ExecutionContext getExecutionContext() { return executionContext; }
    public PublishedAgentDefinition getAgent() { return agent; }
    public WorkflowDefinition getWorkflow() { return workflow; }
    public WorkflowNodeDefinition getNode() { return node; }
    public Map<String, Object> getVariables() { return variables; }
    public List<WorkflowEdgeDefinition> getOutgoingEdges() { return outgoingEdges; }
    public String getNodeRunId() { return nodeRunId; }
}
