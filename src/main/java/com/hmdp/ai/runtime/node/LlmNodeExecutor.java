package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.domain.artifact.Citation;
import com.hmdp.ai.domain.artifact.ResponseBlock;
import com.hmdp.ai.domain.artifact.ResponseBlockType;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.run.UsageSummary;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.runtime.model.AgentModelExecutionPort;
import com.hmdp.ai.runtime.model.GenericModelGateway;
import com.hmdp.ai.runtime.model.ModelInvocation;
import com.hmdp.ai.runtime.model.ModelInvocationContext;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.runtime.prompt.PromptRenderContext;
import com.hmdp.ai.runtime.prompt.PromptRenderer;
import com.hmdp.ai.runtime.prompt.RenderedPrompt;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LlmNodeExecutor implements NodeExecutor {
    private final GenericModelGateway gateway;
    private final PromptRenderer renderer;
    private final PromptRepository prompts;
    private final ObjectMapper mapper;
    private final AiIdGenerator ids;
    private final AgentModelExecutionPort legacyModel;

    @Autowired
    public LlmNodeExecutor(GenericModelGateway gateway, PromptRenderer renderer, PromptRepository prompts,
                           ObjectMapper mapper, AiIdGenerator ids) {
        this.gateway = gateway;
        this.renderer = renderer;
        this.prompts = prompts;
        this.mapper = mapper;
        this.ids = ids;
        this.legacyModel = null;
    }

    /** Compatibility constructor retained for isolated legacy unit tests only. */
    public LlmNodeExecutor(AgentModelExecutionPort legacyModel, ObjectMapper mapper) {
        this.gateway = null;
        this.renderer = null;
        this.prompts = null;
        this.mapper = mapper;
        this.ids = null;
        this.legacyModel = legacyModel;
    }

    @Override
    public java.util.Set<WorkflowNodeType> supportedTypes() {
        return Collections.singleton(WorkflowNodeType.LLM);
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        if (legacyModel != null || context.getExecutionContext() == null) return executeLegacy(context);
        JsonNode configuration = configuration(context.getNode().getConfigurationJson());
        PromptVersion prompt = prompt(context, configuration);
        Map<String, Object> variables = new LinkedHashMap<>(context.getVariables());
        variables.putAll(mappedInputs(configuration.path("inputMapping"), context.getVariables()));
        PromptRenderContext renderContext = new PromptRenderContext(variables,
                java.time.Instant.now(), context.getExecutionContext().getLocale(),
                context.getExecutionContext().getTimezone());
        RenderedPrompt rendered = renderer.render(prompt, renderContext,
                configuration.path("extraInstruction").asText(null));
        String format = configuration.path("responseFormat").asText("TEXT");
        Double temperature = configuration.has("temperatureOverride")
                ? configuration.path("temperatureOverride").asDouble() : null;
        Integer maxTokens = configuration.has("maxOutputTokensOverride")
                ? configuration.path("maxOutputTokensOverride").asInt() : null;
        String modelVersionId = context.getAgent().getVersion().getModelProfileVersionId();
        if (context.getAgent().getModelProfileVersion() != null) {
            modelVersionId = context.getAgent().getModelProfileVersion().getId();
        }
        ModelInvocation invocation = new ModelInvocation(
                new ModelInvocationContext(com.hmdp.ai.domain.observability.InvocationContext.from(
                        context.getExecutionContext(), nodeRunId(context), ids.nextId())),
                modelVersionId, rendered.getSystemPrompt(), renderUserPrompt(variables, rendered.getUserPrompt()),
                format, prompt.getOutputSchema(), temperature, maxTokens,
                configuration.path("streaming").asBoolean(false), rendered.getSummary());
        ModelInvocationResult result = gateway.invoke(invocation);
        AgentRunOutput output = output(result, variables);
        String outputVariable = configuration.path("outputVariable").asText("agentOutput");
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("agentOutput", output);
        updates.put(outputVariable, output);
        if (!"agentOutput".equals(outputVariable)) updates.put("agentOutput", output);
        return NodeExecutionResult.success(mapper.valueToTree(output), null, updates);
    }

    private NodeExecutionResult executeLegacy(NodeExecutionContext context) {
        AgentInputRequest input = new AgentInputRequest();
        Object raw = context.getVariables().get("input");
        if (raw != null) input = mapper.convertValue(raw, AgentInputRequest.class);
        if (input.getText() == null || input.getText().trim().isEmpty()) {
            Object question = context.getVariables().get("question");
            if (question == null) question = context.getVariables().get("text");
            input.setText(question == null ? "" : String.valueOf(question));
        }
        AgentRunOutput output = legacyModel.execute(context.getAgent(), context.getExecutionContext(), input);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("agentOutput", output);
        return NodeExecutionResult.success(mapper.valueToTree(output), null, updates);
    }

    private PromptVersion prompt(NodeExecutionContext context, JsonNode configuration) {
        String id = configuration.path("promptVersionId").asText(null);
        boolean hasVersionId = id != null && !id.trim().isEmpty();
        boolean useAgentDefault = configuration.has("useAgentDefaultPrompt")
                ? configuration.path("useAgentDefaultPrompt").asBoolean()
                : !hasVersionId;
        if (useAgentDefault) {
            return context.getAgent().getPromptVersion();
        }
        if (!hasVersionId) throw new IllegalStateException("PROMPT_VERSION_ID_REQUIRED");
        return prompts.findVersionById(context.getExecutionContext().getTenantId(),
                        context.getExecutionContext().getWorkspaceId(), id)
                .orElseThrow(() -> new IllegalStateException("PROMPT_VERSION_NOT_FOUND"));
    }

    private Map<String, Object> mappedInputs(JsonNode mapping, Map<String, Object> variables) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!mapping.isObject()) return result;
        mapping.fields().forEachRemaining(entry -> {
            String path = entry.getValue().asText();
            Object value = resolve(path, variables);
            if (value != null) result.put(entry.getKey(), value);
        });
        return result;
    }

    private Object resolve(String path, Map<String, Object> variables) {
        String expression = path == null ? "" : path;
        if (expression.startsWith("$.")) expression = expression.substring(2);
        Object value = variables.get(expression);
        if (value != null) return value;
        String[] parts = expression.split("\\.");
        value = variables.get(parts[0]);
        for (int i = 1; i < parts.length && value != null; i++) {
            if (value instanceof Map) value = ((Map<?, ?>) value).get(parts[i]);
            else value = null;
        }
        return value;
    }

    private String renderUserPrompt(Map<String, Object> variables, String rendered) {
        Object question = variables.get("question");
        if (question == null) question = variables.get("text");
        if (question == null) return rendered;
        return "User request:\n" + question + "\n\nWorkflow context:\n" + rendered;
    }

    private AgentRunOutput output(ModelInvocationResult result, Map<String, Object> variables) {
        JsonNode structured = result.getStructuredOutput();
        String answer = structured == null ? result.getContent() : structured.path("answer").asText(result.getContent());
        Map<String, Object> data = structured == null ? Collections.emptyMap() : mapper.convertValue(structured, Map.class);
        List<Citation> citations = citations(structured == null ? variables.get("citations") : structured.get("citations"));
        ResponseBlock block = new ResponseBlock(ResponseBlockType.MARKDOWN, answer, data);
        UsageSummary usage = new UsageSummary(result.getInputTokens(), result.getOutputTokens(), 1, 0, 0,
                result.getLatencyMs());
        return new AgentRunOutput(answer, Collections.singletonList(block), citations,
                Collections.emptyList(), usage, result.isEstimatedUsage()
                        ? Collections.singletonList("ESTIMATED_TOKEN_USAGE") : Collections.emptyList(),
                RunStatus.COMPLETED);
    }

    private List<Citation> citations(Object raw) {
        if (raw == null) return Collections.emptyList();
        try {
            if (raw instanceof JsonNode && ((JsonNode) raw).isNull()) return Collections.emptyList();
            return mapper.convertValue(raw, new TypeReference<List<Citation>>() { });
        } catch (IllegalArgumentException ignored) {
            return new ArrayList<>();
        }
    }

    private JsonNode configuration(String json) {
        try { return mapper.readTree(json == null ? "{}" : json); }
        catch (Exception e) { throw new IllegalStateException("LLM_NODE_CONFIGURATION_INVALID", e); }
    }

    private String nodeRunId(NodeExecutionContext context) {
        return context.getNodeRunId() == null
                ? context.getExecutionContext().getRunId() + ":" + context.getNode().getCode()
                : context.getNodeRunId();
    }
}
