package com.hmdp.ai.runtime.node;

import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

final class NodeExecutorTestSupport {
    private NodeExecutorTestSupport() {
    }

    static NodeExecutionContext context(WorkflowNodeDefinition node, Map<String, Object> variables,
                                        List<WorkflowEdgeDefinition> edges) {
        return context(node, variables, edges, Collections.emptyList(), null);
    }

    static NodeExecutionContext context(WorkflowNodeDefinition node, Map<String, Object> variables,
                                        List<WorkflowEdgeDefinition> edges,
                                        List<com.hmdp.ai.domain.run.AttachmentReference> attachments,
                                        String nodeRunId) {
        WorkflowDefinition workflow = new WorkflowDefinition("v", "t", "w", "wf", 1,
                "{}", "{}", "{}", "{}", "DRAFT", Collections.singletonList(node), edges);
        ExecutionContext execution = new ExecutionContext("t", "w", "u", "s", null,
                "r", "a", 1, "zh-CN", "UTC", attachments, Collections.emptyList(),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)), ExecutionBudget.defaults(),
                Instant.now().plusSeconds(30), Collections.emptyMap(), "trace");
        return new NodeExecutionContext(execution, null, workflow, node, variables, edges, nodeRunId);
    }

    static WorkflowNodeDefinition node(String code, WorkflowNodeType type, String configuration) {
        return new WorkflowNodeDefinition(code, code, type, code, configuration, "{}", "{}", 1000, 1);
    }

    static WorkflowEdgeDefinition edge(String source, String target, String condition, int priority,
                                       String label) {
        return new WorkflowEdgeDefinition(source + target, source, target, condition, priority, label);
    }
}
