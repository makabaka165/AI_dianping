package com.hmdp.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.dto.evaluation.EvaluationExecutionOptions;
import com.hmdp.ai.application.evaluation.EvaluationExecutionResult;
import com.hmdp.ai.application.evaluation.EvaluationExecutor;
import com.hmdp.ai.domain.agent.AgentDefinition;
import com.hmdp.ai.domain.agent.AgentVersion;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.evaluation.EvaluationCase;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.model.ModelProfileVersionRepository;
import com.hmdp.ai.domain.model.ModelType;
import com.hmdp.ai.domain.observability.RunInspectionItem;
import com.hmdp.ai.domain.observability.RunInspectionPort;
import com.hmdp.ai.domain.observability.RunUsageSummary;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.domain.run.NodeRunClaim;
import com.hmdp.ai.domain.run.NodeRunRepository;
import com.hmdp.ai.domain.run.RunEvent;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.workflow.ConditionDslEvaluator;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.domain.workflow.WorkflowRepository;
import com.hmdp.ai.domain.workflow.WorkflowState;
import com.hmdp.ai.domain.workflow.WorkflowStateRepository;
import com.hmdp.ai.domain.workflow.WorkflowStateStatus;
import com.hmdp.ai.domain.workflow.WorkflowValidator;
import com.hmdp.ai.guard.PiiDetectionService;
import com.hmdp.ai.guard.PiiRedactionService;
import com.hmdp.ai.infrastructure.model.DefaultGenericModelGateway;
import com.hmdp.ai.infrastructure.model.ModelClientCache;
import com.hmdp.ai.infrastructure.model.ModelClientFactory;
import com.hmdp.ai.runtime.model.ModelCallRecorder;
import com.hmdp.ai.runtime.model.ModelInvocation;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.runtime.node.EndNodeExecutor;
import com.hmdp.ai.runtime.node.LlmNodeExecutor;
import com.hmdp.ai.runtime.node.NodeExecutionContext;
import com.hmdp.ai.runtime.node.NodeExecutionResult;
import com.hmdp.ai.runtime.node.NodeExecutor;
import com.hmdp.ai.runtime.node.StartNodeExecutor;
import com.hmdp.ai.runtime.node.WorkflowNodeRegistry;
import com.hmdp.ai.runtime.prompt.PromptRenderer;
import com.hmdp.ai.runtime.prompt.PromptVariableResolver;
import com.hmdp.ai.runtime.prompt.PromptVariableValidationService;
import com.hmdp.ai.runtime.workflow.DefaultWorkflowRuntime;
import com.hmdp.ai.runtime.workflow.WorkflowPauseCoordinator;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.validation.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("integration")
class EvaluationRunnerIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ThreadPoolTaskExecutor nodeExecutor = executor();

    @AfterEach
    void shutdownExecutor() {
        nodeExecutor.shutdown();
    }

    @Test
    void executesTargetThroughGenericGatewayAndPersistsRunAndTraceMetrics() {
        ModelProfileVersion profile = modelProfileVersion();
        ModelProfileVersionRepository profiles = mock(ModelProfileVersionRepository.class);
        when(profiles.findById("tenant", "workspace", profile.getId())).thenReturn(Optional.of(profile));
        CapturingModelCallRecorder modelCalls = new CapturingModelCallRecorder();
        ModelClientFactory clientFactory = new ModelClientFactory(null, mapper) {
            @Override
            public com.hmdp.ai.infrastructure.model.ModelClient create(ModelProfileVersion ignored) {
                return invocation -> new ModelInvocationResult("evaluated answer", null, 12, 5,
                        false, 7, new BigDecimal("0.002"), "fake-invocation");
            }
        };
        DefaultGenericModelGateway gateway = new DefaultGenericModelGateway(profiles,
                new ModelClientCache(clientFactory), modelCalls, mapper);
        PromptVariableResolver variableResolver = new PromptVariableResolver();
        PromptRenderer promptRenderer = new PromptRenderer(
                new PromptVariableValidationService(mapper, variableResolver), variableResolver,
                new PiiRedactionService(new PiiDetectionService()));
        PromptRepository prompts = mock(PromptRepository.class);
        LlmNodeExecutor llm = new LlmNodeExecutor(gateway, promptRenderer, prompts, mapper,
                new AiIdGenerator());

        NodeRunRepository nodeRuns = mock(NodeRunRepository.class);
        AtomicInteger nodeSequence = new AtomicInteger();
        when(nodeRuns.start(any(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> new NodeRunClaim("node-run-" + nodeSequence.incrementAndGet(),
                        true, null));
        WorkflowNodeRegistry registry = new WorkflowNodeRegistry(Arrays.asList(
                new StartNodeExecutor(mapper), llm, new EndNodeExecutor(mapper), new PassThroughNodeExecutor()));
        WorkflowStateRepository states = new TestWorkflowStateRepository();
        DefaultWorkflowRuntime workflowRuntime = new DefaultWorkflowRuntime(registry, nodeRuns, states, mapper,
                nodeExecutor, new ConditionDslEvaluator(mapper), new ContentHashService(mapper),
                mock(WorkflowPauseCoordinator.class));

        PublishedAgentDefinition definition = agentDefinition(profile);
        WorkflowDefinition workflow = workflow();
        AgentDefinitionLoader definitions = mock(AgentDefinitionLoader.class);
        when(definitions.load("tenant", "workspace", "general-agent", 3)).thenReturn(definition);
        WorkflowRepository workflows = mock(WorkflowRepository.class);
        when(workflows.findVersion("tenant", "workspace", "workflow-version"))
                .thenReturn(Optional.of(workflow));
        WorkflowValidator validator = mock(WorkflowValidator.class);
        when(validator.validate(workflow)).thenReturn(ValidationResult.valid());

        RunRepository runs = mock(RunRepository.class);
        AtomicReference<AgentRunRecord> createdRun = new AtomicReference<>();
        AtomicReference<String> completedOutput = new AtomicReference<>();
        when(runs.create(any())).thenAnswer(invocation -> {
            AgentRunRecord run = invocation.getArgument(0);
            createdRun.set(run);
            return run;
        });
        when(runs.claimQueued(anyString(), anyString(), anyString())).thenReturn(true);
        doAnswer(invocation -> {
            completedOutput.set(invocation.getArgument(3));
            return null;
        }).when(runs).complete(anyString(), anyString(), anyString(), anyString());

        EvaluationExecutor evaluator = new EvaluationExecutor(definitions, workflows, workflowRuntime,
                validator, mapper, new AiIdGenerator(), runs, new RecorderBackedInspection(modelCalls),
                new ExecutionBudgetFactory(mapper));
        EvaluationCase evaluationCase = new EvaluationCase("case-1", "tenant", "workspace", "dataset",
                "basic", "{\"text\":\"hello\"}", "{\"answer\":\"evaluated answer\"}", "{}", "ACTIVE");

        EvaluationExecutionResult result = evaluator.execute(evaluationCase, "AGENT", "general-agent", 3,
                new EvaluationExecutionOptions(), "tenant", "workspace", "evaluator",
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN, AiPermission.EVALUATION_RUN)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getActual().path("answer").asText()).isEqualTo("evaluated answer");
        assertThat(result.getInputTokens()).isEqualTo(12);
        assertThat(result.getOutputTokens()).isEqualTo(5);
        assertThat(result.getModelCalls()).isEqualTo(1);
        assertThat(result.getCost()).isEqualTo(0.002);
        assertThat(createdRun.get()).isNotNull();
        assertThat(createdRun.get().getId()).isEqualTo(result.getRunId());
        assertThat(createdRun.get().getMetadataJson()).contains("\"evaluation\":true", "case-1");
        assertThat(completedOutput.get()).contains("evaluated answer");
        assertThat(modelCalls.invocation.get()).isNotNull();
        assertThat(modelCalls.invocation.get().getContext().getInvocationContext().getNodeRunId())
                .startsWith("node-run-");
        verify(runs).complete("tenant", "workspace", result.getRunId(), completedOutput.get());
    }

    private WorkflowDefinition workflow() {
        List<WorkflowNodeDefinition> nodes = Arrays.asList(
                node("start", WorkflowNodeType.START, "{}"),
                node("answer", WorkflowNodeType.LLM, "{\"useAgentDefaultPrompt\":true}"),
                node("end", WorkflowNodeType.END, "{}"));
        List<WorkflowEdgeDefinition> edges = Arrays.asList(
                new WorkflowEdgeDefinition("edge-1", "start", "answer", null, 0, null),
                new WorkflowEdgeDefinition("edge-2", "answer", "end", null, 0, null));
        return new WorkflowDefinition("workflow-version", "tenant", "workspace", "workflow", 2,
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                "{\"type\":\"object\"}", "{}", "PUBLISHED", nodes, edges);
    }

    private WorkflowNodeDefinition node(String code, WorkflowNodeType type, String configuration) {
        return new WorkflowNodeDefinition(code, code, type, code, configuration, "{}", "{}", 2000, 1);
    }

    private PublishedAgentDefinition agentDefinition(ModelProfileVersion modelVersion) {
        Instant now = Instant.now();
        AgentDefinition agent = new AgentDefinition("agent-id", "tenant", "workspace", "general-agent",
                "General agent", "evaluation target", 3, "ACTIVE", now, now);
        AgentVersion version = new AgentVersion("agent-version", "tenant", "workspace", "agent-id", 3,
                "General agent", "evaluation target", "model-profile", modelVersion.getId(), "prompt-version",
                "workflow-version", "{}", "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                "{}", "{}", VersionStatus.PUBLISHED, "agent-hash", "published", now, "publisher", now);
        PromptVersion prompt = new PromptVersion("prompt-version", "tenant", "workspace", "prompt", 4,
                "You are a deterministic evaluator.", "Answer {{text}}", null, null, null,
                "{\"type\":\"object\",\"required\":[\"text\"]}", "{\"type\":\"object\"}",
                "{\"type\":\"string\"}", "[]", VersionStatus.PUBLISHED, "prompt-hash", "published",
                now, "publisher", now);
        com.hmdp.ai.domain.run.VersionSnapshot snapshot = new com.hmdp.ai.domain.run.VersionSnapshot(
                "agent-id", 3, "prompt", 4, "workflow", 2, "model-profile", modelVersion.getId(),
                modelVersion.getVersion(), modelVersion.getContentHash(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap());
        return new PublishedAgentDefinition(agent, version, null, modelVersion, prompt,
                "workflow", 2, "PUBLISHED", Collections.emptyList(), Collections.emptyList(), snapshot);
    }

    private ModelProfileVersion modelProfileVersion() {
        Instant now = Instant.now();
        return new ModelProfileVersion("model-version", "tenant", "workspace", "model-profile", 2,
                "OPENAI_COMPATIBLE", "fake-chat", "https://model.invalid/v1", "env:FAKE_MODEL_KEY",
                ModelType.CHAT, "{\"streaming\":false,\"jsonSchema\":true}", "{}", 8192, 1024,
                2000, "{\"maxAttempts\":1}", null, BigDecimal.ZERO, BigDecimal.ZERO,
                "model-hash", "published", "PUBLISHED", now, "publisher", "creator", "publisher", now, now);
    }

    private static ThreadPoolTaskExecutor executor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.initialize();
        return executor;
    }

    private static final class CapturingModelCallRecorder implements ModelCallRecorder {
        private final AtomicReference<ModelInvocation> invocation = new AtomicReference<>();
        private final AtomicReference<ModelInvocationResult> result = new AtomicReference<>();

        @Override
        public void success(ModelProfileVersion profile, ModelInvocation modelInvocation,
                            ModelInvocationResult modelResult, long startedAtMillis) {
            invocation.set(modelInvocation);
            result.set(modelResult);
        }

        @Override
        public void failure(ModelProfileVersion profile, ModelInvocation modelInvocation, String errorCode,
                            long startedAtMillis, long latencyMs) {
            invocation.set(modelInvocation);
        }
    }

    private static final class RecorderBackedInspection implements RunInspectionPort {
        private final CapturingModelCallRecorder recorder;

        private RecorderBackedInspection(CapturingModelCallRecorder recorder) {
            this.recorder = recorder;
        }

        @Override public List<RunInspectionItem> nodeRuns(String tenant, String workspace, String runId) {
            return Collections.emptyList();
        }
        @Override public List<RunInspectionItem> modelCalls(String tenant, String workspace, String runId) {
            return Collections.emptyList();
        }
        @Override public List<RunInspectionItem> toolCalls(String tenant, String workspace, String runId) {
            return Collections.emptyList();
        }
        @Override public List<RunInspectionItem> retrievals(String tenant, String workspace, String runId) {
            return Collections.emptyList();
        }
        @Override public List<RunInspectionItem> artifacts(String tenant, String workspace, String runId) {
            return Collections.emptyList();
        }
        @Override public RunUsageSummary usage(String tenant, String workspace, String runId) {
            ModelInvocationResult result = recorder.result.get();
            return result == null
                    ? new RunUsageSummary(0, 0, 0, 0, BigDecimal.ZERO)
                    : new RunUsageSummary(result.getInputTokens(), result.getOutputTokens(), 1, 0,
                    result.getEstimatedCost());
        }
    }

    private static final class PassThroughNodeExecutor implements NodeExecutor {
        private final Set<WorkflowNodeType> supported;

        private PassThroughNodeExecutor() {
            EnumSet<WorkflowNodeType> values = EnumSet.allOf(WorkflowNodeType.class);
            values.remove(WorkflowNodeType.START);
            values.remove(WorkflowNodeType.LLM);
            values.remove(WorkflowNodeType.END);
            supported = Collections.unmodifiableSet(values);
        }

        @Override public Set<WorkflowNodeType> supportedTypes() { return supported; }
        @Override public NodeExecutionResult execute(NodeExecutionContext context) {
            return NodeExecutionResult.success(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(), null,
                    Collections.emptyMap());
        }
    }

    private static final class TestWorkflowStateRepository implements WorkflowStateRepository {
        private WorkflowState state;

        @Override public Optional<WorkflowState> find(String tenant, String workspace, String runId) {
            return Optional.ofNullable(state);
        }
        @Override public WorkflowState create(WorkflowState value, String actor) {
            state = value;
            return state;
        }
        @Override public WorkflowState saveProgress(WorkflowState value, String actor) {
            state = value;
            return state;
        }
        @Override public WorkflowState saveWaiting(WorkflowState value, WorkflowStateStatus status,
                                                   String node, String tokenHash, Instant expiresAt, String actor) {
            state = value;
            return state;
        }
        @Override public boolean resume(String tenant, String workspace, String runId, String tokenHash,
                                        Map<String, Object> variables, String actor) {
            return false;
        }
        @Override public void complete(String tenant, String workspace, String runId, String actor) {
            state = new WorkflowState(state.getTenantId(), state.getWorkspaceId(), state.getRunId(),
                    state.getWorkflowVersionId(), Collections.emptyList(), state.getVariables(),
                    state.getCompletedNodeKeys(), state.getExecutionCounts(), null,
                    WorkflowStateStatus.COMPLETED, null, state.getStateVersion() + 1);
        }
        @Override public void fail(String tenant, String workspace, String runId, String actor) { }
    }
}
