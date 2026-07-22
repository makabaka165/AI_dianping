package com.hmdp.ai.runtime.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.evaluation.EvaluationExecutionResult;
import com.hmdp.ai.application.evaluation.EvaluationRunSupport;
import com.hmdp.ai.application.evaluation.EvaluationTargetOutput;
import com.hmdp.ai.application.evaluation.EvaluationTargetRequest;
import com.hmdp.ai.application.evaluation.EvaluationTargetRunner;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.tool.ToolCallStatus;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.domain.tool.ToolResult;
import com.hmdp.ai.runtime.tool.ToolExecutionPipeline;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ToolEvaluationRunner implements EvaluationTargetRunner {
    private final AgentDefinitionLoader definitions;
    private final ToolExecutionPipeline tools;
    private final EvaluationRunSupport runs;
    private final AiIdGenerator ids;
    private final ObjectMapper mapper;

    public ToolEvaluationRunner(AgentDefinitionLoader definitions, ToolExecutionPipeline tools,
                                EvaluationRunSupport runs, AiIdGenerator ids, ObjectMapper mapper) {
        this.definitions = definitions;
        this.tools = tools;
        this.runs = runs;
        this.ids = ids;
        this.mapper = mapper;
    }

    @Override
    public String targetType() { return "TOOL"; }

    @Override
    public EvaluationExecutionResult execute(EvaluationTargetRequest request) {
        try {
            String agentId = request.getOptions().getAgentId();
            if (agentId == null || agentId.trim().isEmpty()) {
                throw new IllegalArgumentException("EVALUATION_TOOL_AGENT_REQUIRED");
            }
            int agentVersion = request.getOptions().getAgentVersion() == null
                    ? 1 : request.getOptions().getAgentVersion();
            PublishedAgentDefinition definition = definitions.load(request.getTenantId(),
                    request.getWorkspaceId(), agentId, agentVersion);
            int toolVersion = request.targetVersionOr(1);
            JsonNode caseInput = mapper.readTree(request.getEvaluationCase().getInputJson());
            JsonNode toolInput = caseInput.has("input") ? caseInput.get("input") : caseInput;
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("agent", definition.getVersionSnapshot());
            snapshot.put("toolCode", request.getTargetId());
            snapshot.put("toolVersion", toolVersion);
            EvaluationRunSupport.EvaluationRunDescriptor descriptor = new EvaluationRunSupport
                    .EvaluationRunDescriptor(definition.getAgent().getId(),
                    definition.getVersion().getVersion(), mapper.writeValueAsString(snapshot),
                    definition.getVersion().getExecutionPolicyJson(), Collections.emptyList(),
                    Collections.emptyList(), "zh-CN", "Asia/Shanghai");
            return runs.execute(request, descriptor, session -> {
                String callId = ids.nextId();
                ToolResult result = tools.execute(new ToolInvocation(callId, request.getTargetId(),
                        toolVersion, session.getContext(), toolInput, "evaluation:" + callId,
                        false, session.getNodeRunId(), null));
                ObjectNode actual = mapper.createObjectNode().put("selectedTool", request.getTargetId())
                        .put("toolVersion", toolVersion).put("status", result.getStatus().name());
                actual.set("toolArguments", toolInput);
                actual.set("data", result.getData() == null ? mapper.nullNode() : result.getData());
                actual.set("citationIds", mapper.valueToTree(result.getCitations().stream()
                        .map(value -> value.getCitationId()).collect(Collectors.toList())));
                actual.set("artifactIds", mapper.valueToTree(result.getArtifacts().stream()
                        .map(value -> value.getArtifactId()).collect(Collectors.toList())));
                actual.set("warnings", mapper.valueToTree(result.getWarnings()));
                if (result.getStatus() != ToolCallStatus.SUCCEEDED) {
                    actual.put("errorCode", result.getErrorCode());
                    actual.put("errorMessage", result.getErrorMessage());
                    return EvaluationTargetOutput.failure(actual, 1, result.getErrorCode(),
                            result.getErrorMessage());
                }
                return EvaluationTargetOutput.success(actual, result.getUsage().getInputTokens(),
                        result.getUsage().getOutputTokens(), result.getUsage().getModelCalls(), 1,
                        java.math.BigDecimal.ZERO);
            });
        } catch (Exception error) {
            String message = error.getMessage();
            String code = message != null && message.matches("[A-Z0-9_]{3,64}")
                    ? message : "EVALUATION_TOOL_FAILED";
            return new EvaluationExecutionResult(null, mapper.createObjectNode().put("success", false)
                    .put("errorCode", code).put("errorMessage", message == null ? "target execution failed" : message),
                    0, 0, 0, 0, 0, 0, false, code, message);
        }
    }
}
