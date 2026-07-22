package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.artifact.Citation;
import com.hmdp.ai.domain.artifact.ResponseBlock;
import com.hmdp.ai.domain.artifact.ResponseBlockType;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.run.UsageSummary;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.runtime.model.GenericModelGateway;
import com.hmdp.ai.runtime.model.ModelInvocation;
import com.hmdp.ai.runtime.model.ModelInvocationContext;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.runtime.prompt.PromptRenderContext;
import com.hmdp.ai.runtime.prompt.PromptRenderer;
import com.hmdp.ai.runtime.prompt.RenderedPrompt;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LlmNodeExecutor implements NodeExecutor {
  private final GenericModelGateway gateway;
  private final PromptRenderer renderer;
  private final PromptRepository prompts;
  private final ObjectMapper mapper;
  private final AiIdGenerator ids;
  private final JsonSchemaValidationService schemas;

  @Autowired
  public LlmNodeExecutor(
      GenericModelGateway gateway,
      PromptRenderer renderer,
      PromptRepository prompts,
      ObjectMapper mapper,
      AiIdGenerator ids,
      JsonSchemaValidationService schemas) {
    this.gateway = gateway;
    this.renderer = renderer;
    this.prompts = prompts;
    this.mapper = mapper;
    this.ids = ids;
    this.schemas = schemas;
  }

  /** Compatibility constructor for isolated runtime tests. */
  public LlmNodeExecutor(
      GenericModelGateway gateway,
      PromptRenderer renderer,
      PromptRepository prompts,
      ObjectMapper mapper,
      AiIdGenerator ids) {
    this(gateway, renderer, prompts, mapper, ids, new JsonSchemaValidationService(mapper));
  }

  @Override
  public java.util.Set<WorkflowNodeType> supportedTypes() {
    return Collections.singleton(WorkflowNodeType.LLM);
  }

  @Override
  public NodeExecutionResult execute(NodeExecutionContext context) {
    if (context.getExecutionContext() == null || context.getAgent() == null) {
      throw new IllegalStateException("LLM_EXECUTION_CONTEXT_REQUIRED");
    }
    JsonNode configuration = configuration(context.getNode().getConfigurationJson());
    PromptVersion prompt = prompt(context, configuration);
    Map<String, Object> variables = new LinkedHashMap<>(context.getVariables());
    variables.putAll(mappedInputs(configuration.path("inputMapping"), context.getVariables()));
    PromptRenderContext renderContext =
        new PromptRenderContext(
            variables,
            java.time.Instant.now(),
            context.getExecutionContext().getLocale(),
            context.getExecutionContext().getTimezone());
    RenderedPrompt rendered =
        renderer.render(prompt, renderContext, configuration.path("extraInstruction").asText(null));
    String format = configuration.path("responseFormat").asText("TEXT");
    Double temperature =
        configuration.has("temperatureOverride")
            ? configuration.path("temperatureOverride").asDouble()
            : null;
    Integer maxTokens =
        configuration.has("maxOutputTokensOverride")
            ? configuration.path("maxOutputTokensOverride").asInt()
            : null;
    String modelVersionId = context.getAgent().getVersion().getModelProfileVersionId();
    if (context.getAgent().getModelProfileVersion() != null) {
      modelVersionId = context.getAgent().getModelProfileVersion().getId();
    }
    ModelInvocation invocation =
        new ModelInvocation(
            new ModelInvocationContext(
                com.hmdp.ai.domain.observability.InvocationContext.from(
                    context.getExecutionContext(), nodeRunId(context), ids.nextId())),
            modelVersionId,
            rendered.getSystemPrompt(),
            renderUserPrompt(variables, rendered.getUserPrompt()),
            format,
            prompt.getOutputSchema(),
            temperature,
            maxTokens,
            configuration.path("streaming").asBoolean(false),
            rendered.getSummary());
    ModelInvocationResult result = gateway.invoke(invocation);
    if (!validOutput(result, prompt, format)) {
      return NodeExecutionResult.failure("PROMPT_OUTPUT_SCHEMA_INVALID", false);
    }
    AgentRunOutput output = output(result, variables);
    String outputVariable = configuration.path("outputVariable").asText("agentOutput");
    Map<String, Object> updates = new LinkedHashMap<>();
    updates.put("agentOutput", output);
    if (!"agentOutput".equals(outputVariable)) {
      updates.put(outputVariable, workflowValue(result));
    }
    return NodeExecutionResult.success(mapper.valueToTree(output), null, updates);
  }

  private Object workflowValue(ModelInvocationResult result) {
    JsonNode structured = result.getStructuredOutput();
    if (structured != null && !structured.isNull()) {
      return mapper.convertValue(structured, Object.class);
    }
    return result.getContent();
  }

  private PromptVersion prompt(NodeExecutionContext context, JsonNode configuration) {
    String id = configuration.path("promptVersionId").asText(null);
    boolean hasVersionId = id != null && !id.trim().isEmpty();
    boolean useAgentDefault =
        configuration.has("useAgentDefaultPrompt")
            ? configuration.path("useAgentDefaultPrompt").asBoolean()
            : !hasVersionId;
    if (useAgentDefault) {
      return context.getAgent().getPromptVersion();
    }
    if (!hasVersionId) throw new IllegalStateException("PROMPT_VERSION_ID_REQUIRED");
    return prompts
        .findVersionById(
            context.getExecutionContext().getTenantId(),
            context.getExecutionContext().getWorkspaceId(),
            id)
        .orElseThrow(() -> new IllegalStateException("PROMPT_VERSION_NOT_FOUND"));
  }

  private Map<String, Object> mappedInputs(JsonNode mapping, Map<String, Object> variables) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (!mapping.isObject()) return result;
    mapping
        .fields()
        .forEachRemaining(
            entry -> {
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
    String answer =
        structured == null
            ? result.getContent()
            : structured.path("answer").asText(result.getContent());
    Map<String, Object> data =
        structured == null ? Collections.emptyMap() : mapper.convertValue(structured, Map.class);
    List<Citation> citations =
        citations(structured == null ? variables.get("citations") : structured.get("citations"));
    ResponseBlock block = new ResponseBlock(ResponseBlockType.MARKDOWN, answer, data);
    UsageSummary usage =
        new UsageSummary(
            result.getInputTokens(), result.getOutputTokens(), 1, 0, 0, result.getLatencyMs());
    return new AgentRunOutput(
        answer,
        Collections.singletonList(block),
        citations,
        Collections.emptyList(),
        usage,
        result.isEstimatedUsage()
            ? Collections.singletonList("ESTIMATED_TOKEN_USAGE")
            : Collections.emptyList(),
        RunStatus.COMPLETED);
  }

  private boolean validOutput(ModelInvocationResult result, PromptVersion prompt, String format) {
    if (schemas == null
        || prompt.getOutputSchema() == null
        || prompt.getOutputSchema().trim().isEmpty()) {
      return true;
    }
    JsonNode value;
    if ("JSON".equalsIgnoreCase(format)) {
      value = result.getStructuredOutput();
      if (value == null || value.isNull()) {
        try {
          value = mapper.readTree(result.getContent());
        } catch (Exception ignored) {
          return false;
        }
      }
    } else {
      value = mapper.valueToTree(result.getContent());
    }
    return schemas.validateValue(prompt.getOutputSchema(), value, "promptOutput").isValid();
  }

  private List<Citation> citations(Object raw) {
    if (raw == null) return Collections.emptyList();
    try {
      if (raw instanceof JsonNode && ((JsonNode) raw).isNull()) return Collections.emptyList();
      return mapper.convertValue(raw, new TypeReference<List<Citation>>() {});
    } catch (IllegalArgumentException ignored) {
      return new ArrayList<>();
    }
  }

  private JsonNode configuration(String json) {
    try {
      return mapper.readTree(json == null ? "{}" : json);
    } catch (Exception e) {
      throw new IllegalStateException("LLM_NODE_CONFIGURATION_INVALID", e);
    }
  }

  private String nodeRunId(NodeExecutionContext context) {
    return context.getNodeRunId() == null
        ? context.getExecutionContext().getRunId() + ":" + context.getNode().getCode()
        : context.getNodeRunId();
  }
}
