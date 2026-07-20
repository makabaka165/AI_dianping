package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

@Component
public class StartNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;

    public StartNodeExecutor(ObjectMapper mapper) { this.mapper = mapper; }

    public Set<WorkflowNodeType> supportedTypes() { return Collections.singleton(WorkflowNodeType.START); }

    public NodeExecutionResult execute(NodeExecutionContext context) {
        return NodeExecutionResult.success(mapper.valueToTree(context.getVariables()), null, null);
    }
}
