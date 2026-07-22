package com.hmdp.ai.runtime.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.application.evaluation.EvaluationExecutionResult;
import com.hmdp.ai.application.evaluation.EvaluationRunSupport;
import com.hmdp.ai.application.evaluation.EvaluationTargetOutput;
import com.hmdp.ai.application.evaluation.EvaluationTargetRequest;
import com.hmdp.ai.application.evaluation.EvaluationTargetRunner;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.model.ModelProfileVersionRepository;
import com.hmdp.ai.domain.observability.InvocationContext;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.runtime.model.GenericModelGateway;
import com.hmdp.ai.runtime.model.ModelInvocation;
import com.hmdp.ai.runtime.model.ModelInvocationContext;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.runtime.prompt.PromptRenderContext;
import com.hmdp.ai.runtime.prompt.PromptRenderer;
import com.hmdp.ai.runtime.prompt.RenderedPrompt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PromptEvaluationRunner implements EvaluationTargetRunner {
    private final PromptRepository prompts;
    private final ModelProfileVersionRepository models;
    private final GenericModelGateway gateway;
    private final PromptRenderer renderer;
    private final EvaluationRunSupport runs;
    private final ObjectMapper mapper;

    public PromptEvaluationRunner(PromptRepository prompts, ModelProfileVersionRepository models,
                                  GenericModelGateway gateway, PromptRenderer renderer,
                                  EvaluationRunSupport runs, ObjectMapper mapper) {
        this.prompts = prompts;
        this.models = models;
        this.gateway = gateway;
        this.renderer = renderer;
        this.runs = runs;
        this.mapper = mapper;
    }

    @Override
    public String targetType() { return "PROMPT"; }

    @Override
    public EvaluationExecutionResult execute(EvaluationTargetRequest request) {
        try {
            PromptVersion prompt = resolvePrompt(request);
            if (prompt.getStatus() != VersionStatus.PUBLISHED
                    && prompt.getStatus() != VersionStatus.ARCHIVED) {
                throw new IllegalArgumentException("EVALUATION_PROMPT_NOT_PUBLISHED");
            }
            String modelVersionId = request.getOptions().getModelProfileVersionId();
            if (modelVersionId == null || modelVersionId.trim().isEmpty()) {
                throw new IllegalArgumentException("EVALUATION_MODEL_VERSION_REQUIRED");
            }
            ModelProfileVersion model = models.findById(request.getTenantId(), request.getWorkspaceId(),
                            modelVersionId)
                    .orElseThrow(() -> new IllegalArgumentException("EVALUATION_MODEL_VERSION_NOT_FOUND"));
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("promptId", prompt.getPromptId());
            snapshot.put("promptVersionId", prompt.getId());
            snapshot.put("promptVersion", prompt.getVersion());
            snapshot.put("promptContentHash", prompt.getContentHash());
            snapshot.put("modelProfileId", model.getModelProfileId());
            snapshot.put("modelProfileVersionId", model.getId());
            snapshot.put("modelProfileVersion", model.getVersion());
            snapshot.put("modelContentHash", model.getContentHash());
            EvaluationRunSupport.EvaluationRunDescriptor descriptor = new EvaluationRunSupport
                    .EvaluationRunDescriptor(request.getTargetId(), request.targetVersionOr(prompt.getVersion()),
                    mapper.writeValueAsString(snapshot), "{}", Collections.emptyList(),
                    Collections.emptyList(), "zh-CN", "Asia/Shanghai");
            Map<String, Object> variables = variables(request.getEvaluationCase().getInputJson());
            return runs.execute(request, descriptor, session -> {
                RenderedPrompt rendered = renderer.render(prompt,
                        new PromptRenderContext(variables, Instant.now(), "zh-CN", "Asia/Shanghai"),
                        request.getOptions().getExtraInstruction());
                InvocationContext invocation = InvocationContext.from(session.getContext(),
                        session.getNodeRunId(), session.getInvocationId());
                String responseFormat = request.getOptions().getResponseFormat() == null
                        ? "JSON" : request.getOptions().getResponseFormat();
                ModelInvocationResult result = gateway.invoke(new ModelInvocation(
                        new ModelInvocationContext(invocation), model.getId(), rendered.getSystemPrompt(),
                        rendered.getUserPrompt(), responseFormat, prompt.getOutputSchema(),
                        request.getOptions().getTemperature(), request.getOptions().getMaxOutputTokens(),
                        false, rendered.getSummary()));
                JsonNode actual = result.getStructuredOutput();
                if (actual == null) {
                    actual = mapper.createObjectNode().put("answer", result.getContent())
                            .put("content", result.getContent()).put("status", "SUCCEEDED");
                }
                return EvaluationTargetOutput.success(actual, result.getInputTokens(), result.getOutputTokens(),
                        1, 0, result.getEstimatedCost());
            });
        } catch (Exception error) {
            return failure(error);
        }
    }

    private PromptVersion resolvePrompt(EvaluationTargetRequest request) {
        if (request.getTargetVersion() == null) {
            return prompts.findVersionById(request.getTenantId(), request.getWorkspaceId(), request.getTargetId())
                    .orElseThrow(() -> new IllegalArgumentException("EVALUATION_PROMPT_NOT_FOUND"));
        }
        return prompts.findVersion(request.getTenantId(), request.getWorkspaceId(), request.getTargetId(),
                        request.getTargetVersion())
                .orElseThrow(() -> new IllegalArgumentException("EVALUATION_PROMPT_NOT_FOUND"));
    }

    private Map<String, Object> variables(String inputJson) throws Exception {
        JsonNode input = mapper.readTree(inputJson);
        if (!input.isObject()) throw new IllegalArgumentException("EVALUATION_PROMPT_INPUT_INVALID");
        Map<String, Object> values = mapper.convertValue(input,
                new TypeReference<Map<String, Object>>() { });
        Object nested = values.get("variables");
        if (nested instanceof Map) {
            ((Map<?, ?>) nested).forEach((key, value) -> values.put(String.valueOf(key), value));
        }
        values.put("agentInput", mapper.convertValue(input, Object.class));
        return values;
    }

    private EvaluationExecutionResult failure(Exception error) {
        String message = error.getMessage();
        String code = message != null && message.matches("[A-Z0-9_]{3,64}")
                ? message : "EVALUATION_TARGET_FAILED";
        ObjectNode actual = mapper.createObjectNode().put("success", false).put("errorCode", code)
                .put("errorMessage", message == null ? "target execution failed" : message);
        return new EvaluationExecutionResult(null, actual, 0, 0, 0, 0, 0, 0,
                false, code, message);
    }
}
