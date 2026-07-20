package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.artifact.Citation;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
public class CitationValidationNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;

    public CitationValidationNodeExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Set<WorkflowNodeType> supportedTypes() {
        return Collections.singleton(WorkflowNodeType.CITATION_VALIDATION);
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        Object output = context.getVariables().get("agentOutput");
        if (!(output instanceof AgentRunOutput)) {
            return NodeExecutionResult.failure("WORKFLOW_OUTPUT_REQUIRED", false);
        }
        List<Citation> citations = ((AgentRunOutput) output).getCitations();
        boolean required = false;
        try {
            required = mapper.readTree(context.getNode().getConfigurationJson())
                    .path("required").asBoolean(false);
        } catch (Exception ignored) {
            // Invalid configuration is handled as the safe default below.
        }
        if (required && citations.isEmpty()
                && !context.getVariables().getOrDefault("retrievalResults", Collections.emptyList())
                .equals(Collections.emptyList())) {
            return NodeExecutionResult.failure("CITATION_REQUIRED", false);
        }
        return NodeExecutionResult.success(mapper.valueToTree(citations), null, null);
    }
}
