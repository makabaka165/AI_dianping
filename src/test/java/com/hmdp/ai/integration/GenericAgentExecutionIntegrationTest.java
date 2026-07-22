package com.hmdp.ai.integration;

import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.agent.event.RunEventPublisher;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.observability.AiTraceContext;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.workflow.ConditionDslEvaluator;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.domain.workflow.WorkflowRepository;
import com.hmdp.ai.domain.workflow.WorkflowValidator;
import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.runtime.agent.AgentContextAssembler;
import com.hmdp.ai.runtime.agent.AgentRuntimeProperties;
import com.hmdp.ai.runtime.agent.DefaultAgentRuntime;
import com.hmdp.ai.runtime.agent.WorkflowAgentExecutionEngine;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Tag("integration")
class GenericAgentExecutionIntegrationTest {
    @Test
    void executesPublishedNonShopAgentThroughGenericRuntimeAndPersistsValidatedOutput() throws Exception {
        PromptVersion prompt = RuntimeIntegrationTestSupport.prompt("generic-prompt", 4,
                "You are a general research assistant.", "Answer {{text}}",
                "{\"type\":\"object\",\"required\":[\"text\"]}");
        ModelProfileVersion model = RuntimeIntegrationTestSupport.modelProfileVersion();
        PublishedAgentDefinition definition = RuntimeIntegrationTestSupport.agent(
                "research-assistant", prompt, model, "generic-workflow-version");
        RuntimeIntegrationTestSupport.FixedPromptRepository prompts =
                new RuntimeIntegrationTestSupport.FixedPromptRepository(prompt);

        try (RuntimeIntegrationTestSupport.WorkflowHarness harness =
                     RuntimeIntegrationTestSupport.workflowHarness(prompts, model, invocation ->
                             new ModelInvocationResult("generic runtime answer", null, 14, 6,
                                     false, 8, new BigDecimal("0.002"), "provider-call"))) {
            WorkflowDefinition workflow = workflow();
            JsonSchemaValidationService schemas = new JsonSchemaValidationService(
                    RuntimeIntegrationTestSupport.MAPPER);
            WorkflowValidator validator = new WorkflowValidator(schemas,
                    new ConditionDslEvaluator(RuntimeIntegrationTestSupport.MAPPER),
                    RuntimeIntegrationTestSupport.MAPPER);
            assertThat(validator.validate(workflow).isValid()).isTrue();
            WorkflowRepository workflows = mock(WorkflowRepository.class);
            org.mockito.Mockito.when(workflows.findVersion(RuntimeIntegrationTestSupport.TENANT,
                    RuntimeIntegrationTestSupport.WORKSPACE, "generic-workflow-version"))
                    .thenReturn(Optional.of(workflow));
            WorkflowAgentExecutionEngine engine = new WorkflowAgentExecutionEngine(workflows,
                    harness.runtime, validator);
            AgentDefinitionLoader definitions = (tenantId, workspaceId, agentId, version) -> definition;
            RuntimeIntegrationTestSupport.RecordingRunRepository runs =
                    new RuntimeIntegrationTestSupport.RecordingRunRepository();
            ExecutionBudgetFactory budgets = new ExecutionBudgetFactory(RuntimeIntegrationTestSupport.MAPPER);
            AgentRunRecord queued = queuedRun(definition, budgets);
            runs.create(queued);
            ThreadPoolTaskExecutor agentExecutor = RuntimeIntegrationTestSupport.executor("agent-integration-");
            try {
                RunEventPublisher events = new RunEventPublisher(runs, RuntimeIntegrationTestSupport.MAPPER,
                        mock(com.hmdp.ai.application.agent.event.SseRunEventHub.class));
                DefaultAgentRuntime runtime = new DefaultAgentRuntime(runs, harness.nodeRuns, definitions,
                        new AgentContextAssembler(RuntimeIntegrationTestSupport.MAPPER, budgets), engine,
                        harness.outputValidator, events, RuntimeIntegrationTestSupport.MAPPER, agentExecutor,
                        new AgentRuntimeProperties(), Collections.emptyList(), new AiMetricsService(null),
                        new NoOpTraceContext());

                runtime.enqueue(queued.getTenantId(), queued.getWorkspaceId(), queued.getId());

                assertThat(runs.awaitTerminal(5, TimeUnit.SECONDS)).isTrue();
                AgentRunRecord stored = runs.find(queued.getTenantId(), queued.getWorkspaceId(), queued.getId())
                        .orElseThrow(AssertionError::new);
                assertThat(stored.getStatus()).isEqualTo(RunStatus.COMPLETED);
                assertThat(stored.getOutputJson()).contains("generic runtime answer");
                assertThat(definition.getAgent().getCode()).isEqualTo("research-assistant")
                        .isNotEqualTo("shop-consultant");
                assertThat(stored.getVersionSnapshotJson())
                        .contains(prompt.getPromptId(), "\"promptVersion\":4",
                                model.getContentHash(), model.getId());
                assertThat(harness.modelCalls.invocations).hasSize(1);
                assertThat(harness.modelCalls.invocations.get(0).getModelProfileVersionId())
                        .isEqualTo(model.getId());
                assertThat(harness.modelCalls.invocations.get(0).getSystemPrompt())
                        .isEqualTo(prompt.getSystemPrompt());
                assertThat(harness.modelCalls.invocations.get(0).getContext()
                        .getInvocationContext().getNodeRunId()).isNotBlank();
                assertThat(harness.nodeRuns.starts)
                        .extracting(value -> value.nodeType)
                        .contains("AGENT_WORKFLOW", "LLM", "OUTPUT_VALIDATION");
            } finally {
                agentExecutor.shutdown();
            }
        }
    }

    private WorkflowDefinition workflow() {
        return RuntimeIntegrationTestSupport.workflow("generic-workflow-version", Arrays.asList(
                        RuntimeIntegrationTestSupport.node("start", WorkflowNodeType.START, "{}"),
                        RuntimeIntegrationTestSupport.node("answer", WorkflowNodeType.LLM,
                                "{\"useAgentDefaultPrompt\":true,\"outputVariable\":\"agentOutput\"}"),
                        RuntimeIntegrationTestSupport.node("validate", WorkflowNodeType.OUTPUT_VALIDATION, "{}"),
                        RuntimeIntegrationTestSupport.node("end", WorkflowNodeType.END, "{}")),
                Arrays.asList(
                        RuntimeIntegrationTestSupport.edge("edge-1", "start", "answer"),
                        RuntimeIntegrationTestSupport.edge("edge-2", "answer", "validate"),
                        RuntimeIntegrationTestSupport.edge("edge-3", "validate", "end")));
    }

    private AgentRunRecord queuedRun(PublishedAgentDefinition definition, ExecutionBudgetFactory budgets)
            throws Exception {
        AgentInputRequest input = new AgentInputRequest();
        input.setText("Explain transaction outboxes");
        Instant now = Instant.now();
        return new AgentRunRecord("generic-run", RuntimeIntegrationTestSupport.TENANT,
                RuntimeIntegrationTestSupport.WORKSPACE, "user", "session", "conversation",
                definition.getAgent().getId(), definition.getVersion().getVersion(), RunStatus.QUEUED,
                "BLOCKING", RuntimeIntegrationTestSupport.MAPPER.writeValueAsString(input), null,
                "{}", RuntimeIntegrationTestSupport.MAPPER.writeValueAsString(definition.getVersionSnapshot()),
                budgets.snapshotJson(ExecutionBudget.defaults()), "{\"permissions\":[\"AGENT_RUN\"]}",
                "trace-generic", null, 1, null, null, now, null, null, now.plusSeconds(120), now);
    }

    private static final class NoOpTraceContext implements AiTraceContext {
        @Override
        public void bind(AgentRunRecord run, String nodeRunId) {
        }

        @Override
        public void clear() {
        }
    }
}
