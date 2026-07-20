package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

@Component
public class EndNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;

    public EndNodeExecutor(ObjectMapper mapper) { this.mapper = mapper; }

    public Set<WorkflowNodeType> supportedTypes() { return Collections.singleton(WorkflowNodeType.END); }

    public NodeExecutionResult execute(NodeExecutionContext context) {
        Object output = context.getVariables().get("agentOutput");
        return output == null ? NodeExecutionResult.failure("WORKFLOW_OUTPUT_REQUIRED", false)
                : NodeExecutionResult.success(mapper.valueToTree(output), null, null);
    }
}
