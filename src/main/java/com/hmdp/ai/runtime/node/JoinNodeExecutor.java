package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class JoinNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;

    public JoinNodeExecutor(ObjectMapper mapper) { this.mapper = mapper; }

    public Set<WorkflowNodeType> supportedTypes() { return Collections.singleton(WorkflowNodeType.JOIN); }

    public NodeExecutionResult execute(NodeExecutionContext context) {
        try {
            JsonNode config = mapper.readTree(context.getNode().getConfigurationJson());
            List<JsonNode> values = inputs(config.path("inputVariables"), context.getVariables());
            String mode = config.path("mode").asText("MERGE_OBJECT").toUpperCase(Locale.ROOT);
            JsonNode output;
            switch (mode) {
                case "MERGE_OBJECT": output = merge(values); break;
                case "CONCAT_LIST": output = concat(values); break;
                case "ZIP": output = zip(values); break;
                case "FIRST_SUCCESS": output = values.stream().filter(this::successful).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("JOIN_NO_SUCCESSFUL_INPUT")); break;
                case "ALL_SUCCESS":
                    if (values.stream().anyMatch(value -> !successful(value))) {
                        throw new IllegalArgumentException("JOIN_INPUT_FAILED");
                    }
                    output = mapper.valueToTree(values); break;
                case "PARTIAL_SUCCESS": output = mapper.valueToTree(values.stream()
                        .filter(this::successful).collect(java.util.stream.Collectors.toList())); break;
                default: throw new IllegalArgumentException("JOIN_MODE_UNSUPPORTED");
            }
            String outputVariable = config.path("outputVariable").asText(context.getNode().getCode());
            return NodeExecutionResult.success(output, null,
                    Collections.singletonMap(outputVariable, mapper.convertValue(output, Object.class)));
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.failure(e.getMessage(), false);
        } catch (Exception e) {
            return NodeExecutionResult.failure("JOIN_FAILED", false);
        }
    }

    private List<JsonNode> inputs(JsonNode configured, Map<String, Object> variables) {
        List<JsonNode> values = new ArrayList<>();
        if (configured.isArray() && configured.size() > 0) {
            for (JsonNode name : configured) {
                Object value = variables.get(name.asText());
                if (value == null) values.add(com.fasterxml.jackson.databind.node.NullNode.getInstance());
                else values.add(mapper.valueToTree(value));
            }
        } else {
            variables.forEach((key, value) -> {
                if (!key.startsWith("loop.") && value != null) values.add(mapper.valueToTree(value));
            });
        }
        return values;
    }

    private JsonNode merge(List<JsonNode> values) {
        ObjectNode output = mapper.createObjectNode();
        for (JsonNode value : values) {
            if (!value.isObject()) continue;
            mergeAt(output, (ObjectNode) value, "$");
        }
        return output;
    }

    private void mergeAt(ObjectNode target, ObjectNode source, String path) {
        source.fields().forEachRemaining(entry -> {
            String childPath = path + "." + entry.getKey();
            JsonNode existing = target.get(entry.getKey());
            if (existing == null) target.set(entry.getKey(), entry.getValue());
            else if (existing.isObject() && entry.getValue().isObject()) {
                mergeAt((ObjectNode) existing, (ObjectNode) entry.getValue(), childPath);
            } else if (!existing.equals(entry.getValue())) {
                throw new IllegalArgumentException("JOIN_VARIABLE_CONFLICT:" + childPath);
            }
        });
    }

    private JsonNode concat(List<JsonNode> values) {
        ArrayNode output = mapper.createArrayNode();
        for (JsonNode value : values) {
            if (value.isArray()) value.forEach(output::add);
            else if (!value.isNull()) output.add(value);
        }
        return output;
    }

    private JsonNode zip(List<JsonNode> values) {
        int maximum = values.stream().filter(JsonNode::isArray).mapToInt(JsonNode::size).max().orElse(0);
        ArrayNode output = mapper.createArrayNode();
        for (int index = 0; index < maximum; index++) {
            ArrayNode row = mapper.createArrayNode();
            for (JsonNode value : values) row.add(value.isArray() && index < value.size()
                    ? value.get(index) : com.fasterxml.jackson.databind.node.NullNode.getInstance());
            output.add(row);
        }
        return output;
    }

    private boolean successful(JsonNode value) {
        return value != null && !value.isNull()
                && !(value.isObject() && (value.hasNonNull("errorCode") || value.path("success").isBoolean()
                && !value.path("success").asBoolean()));
    }
}
