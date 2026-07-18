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
        return Collections.singleton(WorkflowNodeType.TOOL);
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        try {
            JsonNode configuration = mapper.readTree(context.getNode().getConfigurationJson());
            String code = configuration.path("toolCode").asText();
            int version = configuration.path("toolVersion").asInt(1);
            JsonNode input = configuration.has("input") ? configuration.get("input")
                    : mapper.valueToTree(context.getVariables());
            boolean approved = Boolean.TRUE.equals(context.getVariables().get("approvedTool." + code));
            String callId = ids.nextId();
            ToolInvocation invocation = new ToolInvocation(callId, code, version,
                    context.getExecutionContext(), input,
                    context.getExecutionContext().getRunId() + ':' + context.getNode().getCode(), approved);
            ToolResult result = tools.execute(invocation);
            if (result.getStatus() == ToolCallStatus.APPROVAL_REQUIRED) {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("pendingToolCode", code);
                pending.put("pendingToolVersion", version);
                return new NodeExecutionResult(NodeRunStatus.WAITING, mapper.valueToTree(pending),
                        Collections.singletonList(context.getNode().getCode()), pending, null, null, null,
                        UsageSummary.empty(0), false, "TOOL_APPROVAL_REQUIRED");
            }
            if (result.getStatus() != ToolCallStatus.SUCCEEDED) {
                return NodeExecutionResult.failure(result.getErrorCode(), result.isRetryable());
            }
            return new NodeExecutionResult(NodeRunStatus.SUCCEEDED, result.getData(), null,
                    Collections.singletonMap(context.getNode().getCode(),
                            mapper.convertValue(result.getData(), Object.class)),
                    result.getArtifacts(), result.getCitations(), result.getWarnings(), result.getUsage(),
                    false, null);
        } catch (Exception e) {
            return NodeExecutionResult.failure("TOOL_NODE_CONFIG_INVALID", false);
        }
    }
}
