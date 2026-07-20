package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

@Component
public class QueryRewriteNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;

    public QueryRewriteNodeExecutor(ObjectMapper mapper) { this.mapper = mapper; }

    public Set<WorkflowNodeType> supportedTypes() {
        return Collections.singleton(WorkflowNodeType.QUERY_REWRITE);
    }

    public NodeExecutionResult execute(NodeExecutionContext context) {
        try {
            JsonNode config = mapper.readTree(context.getNode().getConfigurationJson());
            String input = config.path("inputVariable").asText("text");
            String output = config.path("outputVariable").asText("query");
            String query = String.valueOf(context.getVariables().getOrDefault(input, ""))
                    .trim().replaceAll("\\s+", " ");
            if (query.isEmpty()) return NodeExecutionResult.failure("QUERY_REWRITE_INPUT_REQUIRED", false);
            return NodeExecutionResult.success(mapper.valueToTree(query), null,
                    Collections.singletonMap(output, query));
        } catch (Exception e) {
            return NodeExecutionResult.failure("QUERY_REWRITE_CONFIG_INVALID", false);
        }
    }
}
