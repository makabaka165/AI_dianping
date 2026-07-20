package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.run.NodeRunStatus;
import com.hmdp.ai.domain.run.UsageSummary;
import com.hmdp.ai.domain.tool.ToolCallStatus;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.domain.tool.ToolResult;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.runtime.tool.ToolExecutionPipeline;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class ToolNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;
    private final ToolExecutionPipeline tools;
    private final AiIdGenerator ids;

    public ToolNodeExecutor(ObjectMapper mapper, ToolExecutionPipeline tools, AiIdGenerator ids) {
        this.mapper = mapper;
        this.tools = tools;
        this.ids = ids;
    }

    @Override
    public Set<WorkflowNodeType> supportedTypes() {
        return EnumSet.of(WorkflowNodeType.TOOL, WorkflowNodeType.MCP_TOOL);
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        try {
            JsonNode configuration = mapper.readTree(context.getNode().getConfigurationJson());
            String code = configuration.path("toolCode").asText();
            int version = configuration.path("toolVersion").asInt(1);
            JsonNode input = configuration.has("input") ? configuration.get("input")
                    : mappedInput(configuration.path("inputMapping"), context.getVariables());
            String approvalRequestId = (String) context.getVariables().get("approvalRequest." + code);
            boolean approved = approvalRequestId != null && !approvalRequestId.trim().isEmpty();
            String callId = ids.nextId();
            ToolInvocation invocation = new ToolInvocation(callId, code, version,
                    context.getExecutionContext(), input,
                    context.getExecutionContext().getRunId() + ':' + context.getNode().getCode(), approved,
                    context.getNodeRunId(), approvalRequestId);
            ToolResult result = tools.execute(invocation);
            if (result.getStatus() == ToolCallStatus.CANCELLED) {
                throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
            }
            if (result.getStatus() == ToolCallStatus.APPROVAL_REQUIRED) {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("pendingToolCode", code);
                pending.put("pendingToolVersion", version);
                pending.putAll(approvalMetadata(result.getErrorMessage()));
                return new NodeExecutionResult(NodeRunStatus.WAITING, mapper.valueToTree(pending),
                        Collections.singletonList(context.getNode().getCode()), pending, null, null, null,
                        UsageSummary.empty(0), false, "TOOL_APPROVAL_REQUIRED");
            }
            if (result.getStatus() != ToolCallStatus.SUCCEEDED) {
                return NodeExecutionResult.failure(result.getErrorCode(), result.isRetryable());
            }
            String outputVariable = configuration.path("outputVariable").asText(context.getNode().getCode());
            return new NodeExecutionResult(NodeRunStatus.SUCCEEDED, result.getData(), null,
                    Collections.singletonMap(outputVariable, mapper.convertValue(result.getData(), Object.class)),
                    result.getArtifacts(), result.getCitations(), result.getWarnings(), result.getUsage(),
                    false, null);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            return NodeExecutionResult.failure("TOOL_NODE_CONFIG_INVALID", false);
        }
    }

    private Map<String, Object> approvalMetadata(String message) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (message == null) return values;
        for (String part : message.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0) values.put(part.substring(0, separator), part.substring(separator + 1));
        }
        return values;
    }

    private JsonNode mappedInput(JsonNode mapping, Map<String, Object> variables) {
        if (!mapping.isObject()) return mapper.valueToTree(variables);
        com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
        mapping.fields().forEachRemaining(entry -> result.set(entry.getKey(),
                mapper.valueToTree(resolve(entry.getValue().asText(), variables))));
        return result;
    }

    private Object resolve(String path, Map<String, Object> variables) {
        String expression = path == null ? "" : path;
        if (expression.startsWith("$.")) expression = expression.substring(2);
        String[] parts = expression.split("\\.");
        Object value = variables.get(parts[0]);
        for (int index = 1; index < parts.length && value != null; index++) {
            value = value instanceof Map ? ((Map<?, ?>) value).get(parts[index]) : null;
        }
        return value;
    }
}
