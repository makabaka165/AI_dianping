package com.hmdp.ai.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.agent.AgentDefinition;
import com.hmdp.ai.domain.agent.AgentVersion;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.model.ModelType;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.VersionSnapshot;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.guard.PiiDetectionService;
import com.hmdp.ai.guard.PiiRedactionService;
import com.hmdp.ai.runtime.model.ModelInvocation;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.runtime.node.LlmNodeExecutor;
import com.hmdp.ai.runtime.node.NodeExecutionContext;
import com.hmdp.ai.runtime.node.NodeExecutionResult;
import com.hmdp.ai.runtime.prompt.PromptRenderer;
import com.hmdp.ai.runtime.prompt.PromptVariableResolver;
import com.hmdp.ai.runtime.prompt.PromptVariableValidationService;
import com.hmdp.ai.shared.id.AiIdGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GenericLlmNodeVariableFlowRegressionTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void llmNodeRendersWorkflowVariablesThroughVersionedGenericGateway() {
    AtomicReference<ModelInvocation> captured = new AtomicReference<>();
    LlmNodeExecutor executor =
        new LlmNodeExecutor(
            invocation -> {
              captured.set(invocation);
              return new ModelInvocationResult(
                  "service is reliable", null, 8, 4, false, 5, BigDecimal.ZERO, "fake-call");
            },
            renderer(),
            mock(PromptRepository.class),
            mapper,
            new AiIdGenerator());
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("question", "What is the service quality?");
    variables.put("shopData", Collections.singletonMap("shopId", 7));

    NodeExecutionResult result = executor.execute(context(variables));

    assertThat(captured.get()).isNotNull();
    assertThat(captured.get().getModelProfileVersionId()).isEqualTo("model-version-2");
    assertThat(captured.get().getUserPrompt())
        .contains("What is the service quality?", "shopId=7");
    assertThat(result.getVariableUpdates())
        .containsEntry("firstAnswer", "service is reliable")
        .containsKey("agentOutput");
  }

  private PromptRenderer renderer() {
    PromptVariableResolver resolver = new PromptVariableResolver();
    return new PromptRenderer(
        new PromptVariableValidationService(mapper, resolver),
        resolver,
        new PiiRedactionService(new PiiDetectionService()));
  }

  private NodeExecutionContext context(Map<String, Object> variables) {
    WorkflowNodeDefinition node =
        new WorkflowNodeDefinition(
            "llm",
            "llm",
            WorkflowNodeType.LLM,
            "LLM",
            "{\"useAgentDefaultPrompt\":true,\"inputMapping\":{" 
                + "\"question\":\"$.question\",\"shopData\":\"$.shopData\"},"
                + "\"outputVariable\":\"firstAnswer\",\"responseFormat\":\"TEXT\"}",
            "{}",
            "{}",
            2000,
            1);
    WorkflowDefinition workflow =
        new WorkflowDefinition(
            "workflow-version",
            "tenant",
            "workspace",
            "workflow",
            1,
            "{}",
            "{}",
            "{}",
            "{}",
            "PUBLISHED",
            Collections.singletonList(node),
            Collections.emptyList());
    ExecutionContext execution =
        new ExecutionContext(
            "tenant",
            "workspace",
            "user",
            "session",
            "conversation",
            "run",
            "agent-id",
            3,
            "en-US",
            "UTC",
            Collections.emptyList(),
            Collections.emptyList(),
            new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)),
            ExecutionBudget.defaults(),
            Instant.now().plusSeconds(30),
            variables,
            "trace");
    return new NodeExecutionContext(
        execution, agent(), workflow, node, variables, Collections.emptyList(), "node-run");
  }

  private PublishedAgentDefinition agent() {
    Instant now = Instant.now();
    AgentDefinition definition =
        new AgentDefinition(
            "agent-id",
            "tenant",
            "workspace",
            "general-agent",
            "General agent",
            "General purpose",
            3,
            "ACTIVE",
            now,
            now);
    AgentVersion version =
        new AgentVersion(
            "agent-version",
            "tenant",
            "workspace",
            "agent-id",
            3,
            "General agent",
            "General purpose",
            "model-profile",
            "model-version-2",
            "prompt-version",
            "workflow-version",
            "{}",
            "{}",
            "{}",
            "{}",
            "{}",
            VersionStatus.PUBLISHED,
            "agent-hash",
            "publish",
            now,
            "publisher",
            now);
    PromptVersion prompt =
        new PromptVersion(
            "prompt-version",
            "tenant",
            "workspace",
            "prompt",
            4,
            "Use supplied workflow evidence.",
            "Question: {{question}} Shop: {{shopData}}",
            null,
            null,
            null,
            "{\"type\":\"object\",\"required\":[\"question\",\"shopData\"]}",
            "{}",
            null,
            "[]",
            VersionStatus.PUBLISHED,
            "prompt-hash",
            "publish",
            now,
            "publisher",
            now);
    ModelProfileVersion model =
        new ModelProfileVersion(
            "model-version-2",
            "tenant",
            "workspace",
            "model-profile",
            2,
            "OPENAI_COMPATIBLE",
            "fake-chat",
            "https://model.invalid/v1",
            "env:FAKE_MODEL_KEY",
            ModelType.CHAT,
            "{\"streaming\":false}",
            "{}",
            8192,
            1024,
            2000,
            "{\"maxAttempts\":1}",
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "model-hash",
            "publish",
            "PUBLISHED",
            now,
            "publisher",
            "creator",
            "publisher",
            now,
            now);
    VersionSnapshot snapshot =
        new VersionSnapshot(
            "agent-id",
            3,
            "prompt",
            4,
            "workflow",
            1,
            "model-profile",
            "model-version-2",
            2,
            "model-hash",
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap());
    return new PublishedAgentDefinition(
        definition,
        version,
        null,
        model,
        prompt,
        "workflow",
        1,
        "PUBLISHED",
        Collections.emptyList(),
        Collections.emptyList(),
        snapshot);
  }
}
