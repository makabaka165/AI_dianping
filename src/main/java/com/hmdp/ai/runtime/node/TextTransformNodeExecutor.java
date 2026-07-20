package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

@Component
public class TextTransformNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;

    public TextTransformNodeExecutor(ObjectMapper mapper) { this.mapper = mapper; }

    public Set<WorkflowNodeType> supportedTypes() {
        return Collections.singleton(WorkflowNodeType.TEXT_TRANSFORM);
    }

    public NodeExecutionResult execute(NodeExecutionContext context) {
        try {
            JsonNode config = mapper.readTree(context.getNode().getConfigurationJson());
            String inputVariable = config.path("inputVariable").asText("text");
            String outputVariable = config.path("outputVariable").asText(inputVariable);
            String value = String.valueOf(context.getVariables().getOrDefault(inputVariable, ""));
            for (JsonNode operation : config.path("operations")) {
                switch (operation.asText().toUpperCase(Locale.ROOT)) {
                    case "TRIM": value = value.trim(); break;
                    case "NORMALIZE_WHITESPACE": value = value.replaceAll("\\s+", " ").trim(); break;
                    case "LOWERCASE": value = value.toLowerCase(Locale.ROOT); break;
                    case "UPPERCASE": value = value.toUpperCase(Locale.ROOT); break;
                    default: throw new IllegalArgumentException("TEXT_TRANSFORM_OPERATION_UNSUPPORTED");
                }
            }
            int maxChars = config.path("maxChars").asInt(Integer.MAX_VALUE);
            if (value.length() > maxChars) value = value.substring(0, maxChars);
            return NodeExecutionResult.success(mapper.valueToTree(value), null,
                    Collections.singletonMap(outputVariable, value));
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.failure(e.getMessage(), false);
        } catch (Exception e) {
            return NodeExecutionResult.failure("TEXT_TRANSFORM_CONFIG_INVALID", false);
        }
    }
}
