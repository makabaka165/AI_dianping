package com.hmdp.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.event.SseRunEventHub;
import com.hmdp.ai.domain.agent.AgentDefinition;
import com.hmdp.ai.domain.agent.AgentVersion;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.model.ModelProfileVersionRepository;
import com.hmdp.ai.domain.model.ModelType;
import com.hmdp.ai.domain.prompt.PromptDefinition;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.NodeRunClaim;
import com.hmdp.ai.domain.run.NodeRunRepository;
import com.hmdp.ai.domain.run.NodeRunStatus;
import com.hmdp.ai.domain.run.RunEvent;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.run.VersionSnapshot;
import com.hmdp.ai.domain.workflow.ConditionDslEvaluator;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.domain.workflow.WorkflowState;
import com.hmdp.ai.domain.workflow.WorkflowStateRepository;
import com.hmdp.ai.domain.workflow.WorkflowStateStatus;
import com.hmdp.ai.guard.PiiDetectionService;
import com.hmdp.ai.guard.PiiRedactionService;
import com.hmdp.ai.infrastructure.model.DefaultGenericModelGateway;
import com.hmdp.ai.infrastructure.model.ModelClient;
import com.hmdp.ai.infrastructure.model.ModelClientCache;
import com.hmdp.ai.infrastructure.model.ModelClientFactory;
import com.hmdp.ai.runtime.agent.AgentOutputValidator;
import com.hmdp.ai.runtime.model.ModelCallRecorder;
import com.hmdp.ai.runtime.model.ModelInvocation;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.runtime.node.EndNodeExecutor;
import com.hmdp.ai.runtime.node.LlmNodeExecutor;
import com.hmdp.ai.runtime.node.NodeExecutionContext;
import com.hmdp.ai.runtime.node.NodeExecutionResult;
import com.hmdp.ai.runtime.node.NodeExecutor;
import com.hmdp.ai.runtime.node.OutputNodeExecutor;
import com.hmdp.ai.runtime.node.StartNodeExecutor;
import com.hmdp.ai.runtime.node.WorkflowNodeRegistry;
import com.hmdp.ai.runtime.prompt.PromptRenderer;
import com.hmdp.ai.runtime.prompt.PromptVariableResolver;
import com.hmdp.ai.runtime.prompt.PromptVariableValidationService;
import com.hmdp.ai.runtime.workflow.DefaultWorkflowRuntime;
import com.hmdp.ai.runtime.workflow.WorkflowPauseCoordinator;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

final class RuntimeIntegrationTestSupport {
    static final String TENANT = "tenant";
    static final String WORKSPACE = "workspace";
    static final ObjectMapper MAPPER = new ObjectMapper();

    private RuntimeIntegrationTestSupport() {
    }

    static WorkflowHarness workflowHarness(PromptRepository prompts, ModelProfileVersion model,
                                           Function<ModelInvocation, ModelInvocationResult> modelOperation) {
        return workflowHarness(prompts, model, modelOperation, new NodeExecutor[0]);
    }

    static WorkflowHarness workflowHarness(PromptRepository prompts, ModelProfileVersion model,
                                           Function<ModelInvocation, ModelInvocationResult> modelOperation,
                                           NodeExecutor... additionalExecutors) {
        FixedModelProfileVersionRepository profiles = new FixedModelProfileVersionRepository(model);
        RecordingModelCallRecorder modelCalls = new RecordingModelCallRecorder();
        ModelClientFactory clientFactory = new ModelClientFactory(null, MAPPER) {
            @Override
            public ModelClient create(ModelProfileVersion ignored) {
                return modelOperation::apply;
            }
        };
        DefaultGenericModelGateway gateway = new DefaultGenericModelGateway(profiles,
                new ModelClientCache(clientFactory), modelCalls, MAPPER);
        PromptVariableResolver resolver = new PromptVariableResolver();
        PromptRenderer renderer = new PromptRenderer(
                new PromptVariableValidationService(MAPPER, resolver), resolver,
                new PiiRedactionService(new PiiDetectionService()));
        LlmNodeExecutor llm = new LlmNodeExecutor(gateway, renderer, prompts, MAPPER,
                new AiIdGenerator());
        JsonSchemaValidationService schemas = new JsonSchemaValidationService(MAPPER);
        AgentOutputValidator outputValidator = new AgentOutputValidator(MAPPER, schemas);
        Set<WorkflowNodeType> realTypes = EnumSet.of(WorkflowNodeType.START, WorkflowNodeType.LLM,
                WorkflowNodeType.OUTPUT_VALIDATION, WorkflowNodeType.END);
        List<NodeExecutor> executors = new ArrayList<>();
        executors.add(new StartNodeExecutor(MAPPER));
        executors.add(llm);
        executors.add(new OutputNodeExecutor(outputValidator, MAPPER));
        executors.add(new EndNodeExecutor(MAPPER));
        for (NodeExecutor executor : additionalExecutors) {
            realTypes.addAll(executor.supportedTypes());
            executors.add(executor);
        }
        executors.add(new PassThroughNodeExecutor(realTypes));
        WorkflowNodeRegistry registry = new WorkflowNodeRegistry(executors);
        RecordingNodeRunRepository nodeRuns = new RecordingNodeRunRepository();
        RecordingWorkflowStateRepository states = new RecordingWorkflowStateRepository();
        RecordingRunRepository pauseRuns = new RecordingRunRepository();
        ThreadPoolTaskExecutor executor = executor("workflow-integration-");
        WorkflowPauseCoordinator pauses = new WorkflowPauseCoordinator(states, pauseRuns);
        DefaultWorkflowRuntime runtime = new DefaultWorkflowRuntime(registry, nodeRuns, states, MAPPER,
                executor, new ConditionDslEvaluator(MAPPER), new ContentHashService(MAPPER), pauses);
        return new WorkflowHarness(runtime, nodeRuns, states, pauseRuns, modelCalls, outputValidator, executor);
    }

    static ModelProfileVersion modelProfileVersion() {
        Instant now = Instant.now();
        return new ModelProfileVersion("model-version", TENANT, WORKSPACE, "model-profile", 2,
                "OPENAI_COMPATIBLE", "fake-chat", "https://model.invalid/v1", "env:FAKE_MODEL_KEY",
                ModelType.CHAT, "{\"streaming\":false,\"jsonSchema\":true}", "{}", 8192, 1024,
                2000, "{\"maxAttempts\":1}", null, BigDecimal.ZERO, BigDecimal.ZERO,
                "model-content-hash", "published", "PUBLISHED", now, "publisher", "creator",
                "publisher", now, now);
    }

    static PromptVersion prompt(String id, int version, String system, String task,
                                String variablesSchema) {
        Instant now = Instant.now();
        return new PromptVersion(id, TENANT, WORKSPACE, "prompt-" + id, version, system, task,
                null, null, null, variablesSchema, "{\"type\":\"object\"}",
                "{\"type\":\"string\"}", "[]", VersionStatus.PUBLISHED,
                id + "-content-hash", "published", now, "publisher", now);
    }

    static PublishedAgentDefinition agent(String code, PromptVersion prompt, ModelProfileVersion model,
                                          String workflowVersionId) {
        Instant now = Instant.now();
        AgentDefinition definition = new AgentDefinition("agent-id", TENANT, WORKSPACE, code,
                "General agent", "Non-shop integration agent", 3, "ACTIVE", now, now);
        String outputSchema = "{\"type\":\"object\",\"required\":[\"answer\"]}";
        AgentVersion version = new AgentVersion("agent-version", TENANT, WORKSPACE, definition.getId(), 3,
                definition.getName(), definition.getDescription(), "model-profile", model.getId(),
                prompt.getId(), workflowVersionId, "{}", "{\"type\":\"object\"}", outputSchema,
                "{\"maxWorkflowNodes\":64,\"maxRunDurationSeconds\":120}", "{}",
                VersionStatus.PUBLISHED, "agent-content-hash", "published", now, "publisher", now);
        VersionSnapshot snapshot = new VersionSnapshot(definition.getId(), definition.getCode(),
                version.getVersion(), prompt.getPromptId(), prompt.getVersion(), "workflow", 1,
                "model-profile", model.getId(), model.getVersion(), model.getContentHash(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        return new PublishedAgentDefinition(definition, version, null, model, prompt, "workflow", 1,
                "PUBLISHED", Collections.emptyList(), Collections.emptyList(), snapshot);
    }

    static WorkflowDefinition workflow(String id, List<WorkflowNodeDefinition> nodes,
                                       List<WorkflowEdgeDefinition> edges) {
        return new WorkflowDefinition(id, TENANT, WORKSPACE, "workflow", 1,
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                "{\"type\":\"object\"}", "{}", "PUBLISHED", nodes, edges);
    }

    static WorkflowNodeDefinition node(String code, WorkflowNodeType type, String configuration) {
        return new WorkflowNodeDefinition(code, code, type, code, configuration, "{}", "{}", 2000, 1);
    }

    static WorkflowEdgeDefinition edge(String id, String source, String target) {
        return new WorkflowEdgeDefinition(id, source, target, null, 0, null);
    }

    static ThreadPoolTaskExecutor executor(String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.initialize();
        return executor;
    }

    static final class WorkflowHarness implements AutoCloseable {
        final DefaultWorkflowRuntime runtime;
        final RecordingNodeRunRepository nodeRuns;
        final RecordingWorkflowStateRepository states;
        final RecordingRunRepository runs;
        final RecordingModelCallRecorder modelCalls;
        final AgentOutputValidator outputValidator;
        private final ThreadPoolTaskExecutor executor;

        private WorkflowHarness(DefaultWorkflowRuntime runtime, RecordingNodeRunRepository nodeRuns,
                                RecordingWorkflowStateRepository states,
                                RecordingRunRepository runs,
                                RecordingModelCallRecorder modelCalls,
                                AgentOutputValidator outputValidator,
                                ThreadPoolTaskExecutor executor) {
            this.runtime = runtime;
            this.nodeRuns = nodeRuns;
            this.states = states;
            this.runs = runs;
            this.modelCalls = modelCalls;
            this.outputValidator = outputValidator;
            this.executor = executor;
        }

        @Override
        public void close() {
            executor.shutdown();
        }
    }

    static final class FixedPromptRepository implements PromptRepository {
        private final Map<String, PromptVersion> versions;

        FixedPromptRepository(PromptVersion... values) {
            versions = java.util.Arrays.stream(values).collect(Collectors.toMap(PromptVersion::getId,
                    value -> value));
        }

        @Override
        public Optional<PromptVersion> findVersionById(String tenantId, String workspaceId, String versionId) {
            if (!TENANT.equals(tenantId) || !WORKSPACE.equals(workspaceId)) return Optional.empty();
            return Optional.ofNullable(versions.get(versionId));
        }

        @Override
        public Optional<PromptVersion> findVersion(String tenantId, String workspaceId, String promptId,
                                                   int version) {
            return versions.values().stream().filter(value -> value.getPromptId().equals(promptId)
                    && value.getVersion() == version).findFirst();
        }

        @Override
        public List<PromptVersion> findVersions(String tenantId, String workspaceId, String promptId,
                                                int offset, int limit) {
            return versions.values().stream().filter(value -> value.getPromptId().equals(promptId))
                    .skip(offset).limit(limit).collect(Collectors.toList());
        }

        @Override
        public PromptDefinition create(PromptDefinition prompt, String actorId) {
            throw new AssertionError("prompt mutation is not expected in a runtime test");
        }

        @Override
        public Optional<PromptDefinition> findById(String tenantId, String workspaceId, String promptId) {
            return Optional.empty();
        }

        @Override
        public List<PromptDefinition> findPage(String tenantId, String workspaceId, int offset, int limit) {
            return Collections.emptyList();
        }

        @Override
        public long count(String tenantId, String workspaceId) {
            return versions.size();
        }

        @Override
        public int lockAndNextVersion(String tenantId, String workspaceId, String promptId) {
            throw new AssertionError("prompt mutation is not expected in a runtime test");
        }

        @Override
        public PromptVersion createVersion(PromptVersion version, String actorId) {
            throw new AssertionError("prompt mutation is not expected in a runtime test");
        }

        @Override
        public PromptVersion publish(String tenantId, String workspaceId, String promptId, int version,
                                     String actorId) {
            throw new AssertionError("prompt mutation is not expected in a runtime test");
        }
    }

    static final class RecordingModelCallRecorder implements ModelCallRecorder {
        final List<ModelInvocation> invocations = new CopyOnWriteArrayList<>();
        final List<ModelInvocationResult> results = new CopyOnWriteArrayList<>();
        final List<String> failures = new CopyOnWriteArrayList<>();

        @Override
        public void success(ModelProfileVersion profile, ModelInvocation invocation,
                            ModelInvocationResult result, long startedAtMillis) {
            invocations.add(invocation);
            results.add(result);
        }

        @Override
        public void failure(ModelProfileVersion profile, ModelInvocation invocation, String errorCode,
                            long startedAtMillis, long latencyMs) {
            invocations.add(invocation);
            failures.add(errorCode);
        }
    }

    static final class RecordingNodeRunRepository implements NodeRunRepository {
        private final AtomicInteger sequence = new AtomicInteger();
        final List<NodeStart> starts = new CopyOnWriteArrayList<>();
        final Map<String, String> completedOutputs = new ConcurrentHashMap<>();
        final Map<String, NodeRunStatus> terminalStatuses = new ConcurrentHashMap<>();

        @Override
        public NodeRunClaim start(com.hmdp.ai.domain.run.ExecutionContext context, String nodeId,
                                  String nodeType, String inputJson, String idempotencyKey) {
            String nodeRunId = "node-run-" + sequence.incrementAndGet();
            starts.add(new NodeStart(nodeRunId, nodeId, nodeType, idempotencyKey));
            return new NodeRunClaim(nodeRunId, true, null);
        }

        @Override
        public void complete(String tenantId, String workspaceId, String nodeRunId, String outputJson,
                             String usageJson) {
            completedOutputs.put(nodeRunId, outputJson);
            terminalStatuses.put(nodeRunId, NodeRunStatus.SUCCEEDED);
        }

        @Override
        public void waitForResume(String tenantId, String workspaceId, String nodeRunId, String outputJson) {
            completedOutputs.put(nodeRunId, outputJson);
            terminalStatuses.put(nodeRunId, NodeRunStatus.WAITING);
        }

        @Override
        public void fail(String tenantId, String workspaceId, String nodeRunId, NodeRunStatus status,
                         String errorCode, String errorMessage, boolean retryable) {
            terminalStatuses.put(nodeRunId, status);
        }
    }

    static final class NodeStart {
        final String nodeRunId;
        final String nodeId;
        final String nodeType;
        final String idempotencyKey;

        private NodeStart(String nodeRunId, String nodeId, String nodeType, String idempotencyKey) {
            this.nodeRunId = nodeRunId;
            this.nodeId = nodeId;
            this.nodeType = nodeType;
            this.idempotencyKey = idempotencyKey;
        }
    }

    static final class RecordingWorkflowStateRepository implements WorkflowStateRepository {
        private final Map<String, WorkflowState> states = new ConcurrentHashMap<>();
        private final Map<String, String> resumeTokenHashes = new ConcurrentHashMap<>();
        private final Map<String, Instant> resumeExpiresAt = new ConcurrentHashMap<>();

        @Override
        public Optional<WorkflowState> find(String tenantId, String workspaceId, String runId) {
            return Optional.ofNullable(states.get(runId));
        }

        @Override
        public WorkflowState create(WorkflowState initialState, String actorId) {
            states.put(initialState.getRunId(), initialState);
            return initialState;
        }

        @Override
        public WorkflowState saveProgress(WorkflowState state, String actorId) {
            WorkflowState stored = copy(state, state.getCurrentNodeCodes(), state.getVariables(),
                    state.getCompletedNodeKeys(), state.getExecutionCounts(), null,
                    WorkflowStateStatus.RUNNING, null);
            states.put(state.getRunId(), stored);
            return stored;
        }

        @Override
        public WorkflowState saveWaiting(WorkflowState state, WorkflowStateStatus waitingStatus,
                                         String waitingNodeCode, String resumeTokenHash, Instant expiresAt,
                                         String actorId) {
            WorkflowState stored = copy(state, state.getCurrentNodeCodes(), state.getVariables(),
                    state.getCompletedNodeKeys(), state.getExecutionCounts(), waitingNodeCode,
                    waitingStatus, expiresAt);
            states.put(state.getRunId(), stored);
            resumeTokenHashes.put(state.getRunId(), resumeTokenHash);
            resumeExpiresAt.put(state.getRunId(), expiresAt);
            return stored;
        }

        @Override
        public boolean resume(String tenantId, String workspaceId, String runId, String resumeTokenHash,
                              Map<String, Object> resumeVariables, String actorId) {
            WorkflowState current = states.get(runId);
            Instant expiresAt = resumeExpiresAt.get(runId);
            if (current == null || !resumeTokenHash.equals(resumeTokenHashes.get(runId))
                    || expiresAt == null || !expiresAt.isAfter(Instant.now())) return false;
            Map<String, Object> variables = new LinkedHashMap<>(current.getVariables());
            variables.putAll(resumeVariables);
            states.put(runId, copy(current, current.getCurrentNodeCodes(), variables,
                    current.getCompletedNodeKeys(), current.getExecutionCounts(), null,
                    WorkflowStateStatus.RUNNING, null));
            resumeTokenHashes.remove(runId);
            resumeExpiresAt.remove(runId);
            return true;
        }

        @Override
        public void complete(String tenantId, String workspaceId, String runId, String actorId) {
            WorkflowState current = states.get(runId);
            states.put(runId, copy(current, Collections.emptyList(), current.getVariables(),
                    current.getCompletedNodeKeys(), current.getExecutionCounts(), null,
                    WorkflowStateStatus.COMPLETED, null));
            resumeTokenHashes.remove(runId);
            resumeExpiresAt.remove(runId);
        }

        @Override
        public void fail(String tenantId, String workspaceId, String runId, String actorId) {
            WorkflowState current = states.get(runId);
            if (current != null) {
                states.put(runId, copy(current, current.getCurrentNodeCodes(), current.getVariables(),
                        current.getCompletedNodeKeys(), current.getExecutionCounts(), null,
                        WorkflowStateStatus.FAILED, null));
            }
        }

        private WorkflowState copy(WorkflowState source, List<String> nodes, Map<String, Object> variables,
                                   Set<String> completed, Map<String, Integer> counts, String waitingNode,
                                   WorkflowStateStatus status, Instant expiresAt) {
            return new WorkflowState(source.getTenantId(), source.getWorkspaceId(), source.getRunId(),
                    source.getWorkflowVersionId(), nodes, variables, completed, counts, waitingNode, status,
                    expiresAt, source.getStateVersion() + 1);
        }
    }

    static final class RecordingRunRepository implements RunRepository {
        private final Map<String, AgentRunRecord> runs = new ConcurrentHashMap<>();
        private final Map<String, List<RunEvent>> events = new ConcurrentHashMap<>();
        private final Map<String, String> resumeTokenHashes = new ConcurrentHashMap<>();
        private final Map<String, Instant> resumeExpiresAt = new ConcurrentHashMap<>();
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public AgentRunRecord create(AgentRunRecord run) {
            runs.put(run.getId(), run);
            return run;
        }

        @Override
        public Optional<AgentRunRecord> find(String tenantId, String workspaceId, String runId) {
            AgentRunRecord run = runs.get(runId);
            if (run == null || !tenantId.equals(run.getTenantId()) || !workspaceId.equals(run.getWorkspaceId())) {
                return Optional.empty();
            }
            return Optional.of(run);
        }

        @Override
        public List<AgentRunRecord> findPage(String tenantId, String workspaceId, String userId, String agentId,
                                             RunStatus status, Instant createdFrom, Instant createdTo,
                                             int offset, int limit) {
            return runs.values().stream()
                    .filter(run -> tenantId.equals(run.getTenantId()) && workspaceId.equals(run.getWorkspaceId()))
                    .filter(run -> userId == null || userId.equals(run.getUserId()))
                    .filter(run -> agentId == null || agentId.equals(run.getAgentId()))
                    .filter(run -> status == null || status == run.getStatus())
                    .filter(run -> createdFrom == null || !run.getCreatedAt().isBefore(createdFrom))
                    .filter(run -> createdTo == null || !run.getCreatedAt().isAfter(createdTo))
                    .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                    .skip(Math.max(0, offset))
                    .limit(Math.max(0, limit))
                    .collect(Collectors.toList());
        }

        @Override
        public long countPage(String tenantId, String workspaceId, String userId, String agentId, RunStatus status,
                              Instant createdFrom, Instant createdTo) {
            return findPage(tenantId, workspaceId, userId, agentId, status, createdFrom, createdTo, 0, Integer.MAX_VALUE)
                    .size();
        }

        @Override
        public boolean claimQueued(String tenantId, String workspaceId, String runId) {
            AgentRunRecord current = find(tenantId, workspaceId, runId).orElse(null);
            if (current == null || current.getStatus() != RunStatus.QUEUED) return false;
            runs.put(runId, copy(current, RunStatus.RUNNING, current.getOutputJson(), null, null));
            return true;
        }

        @Override
        public void complete(String tenantId, String workspaceId, String runId, String outputJson) {
            AgentRunRecord current = find(tenantId, workspaceId, runId).orElseThrow(AssertionError::new);
            runs.put(runId, copy(current, RunStatus.COMPLETED, outputJson, null, null));
            terminal.countDown();
        }

        @Override
        public void fail(String tenantId, String workspaceId, String runId, String errorCode,
                         String errorMessage, RunStatus terminalStatus) {
            AgentRunRecord current = find(tenantId, workspaceId, runId).orElseThrow(AssertionError::new);
            runs.put(runId, copy(current, terminalStatus, current.getOutputJson(), errorCode, errorMessage));
            terminal.countDown();
        }

        @Override
        public boolean cancel(String tenantId, String workspaceId, String runId, String actorId) {
            AgentRunRecord current = find(tenantId, workspaceId, runId).orElse(null);
            if (current == null || current.getStatus().isTerminal()) return false;
            runs.put(runId, copy(current, RunStatus.CANCELLED, current.getOutputJson(),
                    "RUN_CANCELLED", "run cancelled"));
            terminal.countDown();
            return true;
        }

        @Override
        public boolean markWaiting(String tenantId, String workspaceId, String runId, RunStatus waitingStatus,
                                   String resumeTokenHash, Instant expiresAt, String actorId) {
            AgentRunRecord current = find(tenantId, workspaceId, runId).orElse(null);
            if (current == null || current.getStatus().isTerminal()) return false;
            runs.put(runId, copy(current, waitingStatus, current.getOutputJson(), null, null));
            resumeTokenHashes.put(runId, resumeTokenHash);
            resumeExpiresAt.put(runId, expiresAt);
            return true;
        }

        @Override
        public boolean resumeWaiting(String tenantId, String workspaceId, String runId, String resumeTokenHash,
                                     String resumeDataJson, String actorId) {
            AgentRunRecord current = find(tenantId, workspaceId, runId).orElse(null);
            if (current == null || (current.getStatus() != RunStatus.WAITING_FOR_USER
                    && current.getStatus() != RunStatus.WAITING_FOR_APPROVAL)
                    || !resumeTokenHash.equals(resumeTokenHashes.get(runId))
                    || !resumeExpiresAt.getOrDefault(runId, Instant.EPOCH).isAfter(Instant.now())) return false;
            runs.put(runId, copy(current, RunStatus.QUEUED, current.getOutputJson(), null, null));
            resumeTokenHashes.remove(runId);
            resumeExpiresAt.remove(runId);
            return true;
        }

        @Override
        public long appendEvent(String tenantId, String workspaceId, String runId, String eventType,
                                String payloadJson) {
            List<RunEvent> runEvents = events.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
            long sequence = runEvents.size() + 1L;
            runEvents.add(new RunEvent(sequence, runId, eventType, payloadJson, Instant.now()));
            return sequence;
        }

        @Override
        public List<RunEvent> findEvents(String tenantId, String workspaceId, String runId,
                                         long afterSequence, int limit) {
            return events.getOrDefault(runId, Collections.emptyList()).stream()
                    .filter(value -> value.getSequence() > afterSequence).limit(limit)
                    .collect(Collectors.toList());
        }

        @Override
        public List<AgentRunRecord> findRecoverable(int limit) {
            return Collections.emptyList();
        }

        @Override
        public int requeueInterruptedRuns() {
            return 0;
        }

        boolean awaitTerminal(long timeout, TimeUnit unit) throws InterruptedException {
            return terminal.await(timeout, unit);
        }

        private AgentRunRecord copy(AgentRunRecord source, RunStatus status, String outputJson,
                                    String errorCode, String errorMessage) {
            Instant finished = status.isTerminal() ? Instant.now() : null;
            Instant started = status == RunStatus.RUNNING && source.getStartedAt() == null
                    ? Instant.now() : source.getStartedAt();
            return new AgentRunRecord(source.getId(), source.getTenantId(), source.getWorkspaceId(),
                    source.getUserId(), source.getSessionId(), source.getConversationId(), source.getAgentId(),
                    source.getAgentVersion(), status, source.getResponseMode(), source.getInputJson(), outputJson,
                    source.getMetadataJson(), source.getVersionSnapshotJson(), source.getBudgetJson(),
                    source.getAuthorizationJson(), source.getTraceId(), source.getRetryOfRunId(),
                    source.getAttempt(), errorCode, errorMessage, source.getQueuedAt(), started, finished,
                    source.getDeadlineAt(), source.getCreatedAt());
        }
    }

    private static final class FixedModelProfileVersionRepository implements ModelProfileVersionRepository {
        private final ModelProfileVersion model;

        private FixedModelProfileVersionRepository(ModelProfileVersion model) {
            this.model = model;
        }

        @Override
        public Optional<ModelProfileVersion> findById(String tenantId, String workspaceId, String id) {
            if (TENANT.equals(tenantId) && WORKSPACE.equals(workspaceId) && model.getId().equals(id)) {
                return Optional.of(model);
            }
            return Optional.empty();
        }

        @Override
        public Optional<ModelProfileVersion> findByProfileAndVersion(String tenantId, String workspaceId,
                                                                      String profileId, int version) {
            return model.getModelProfileId().equals(profileId) && model.getVersion() == version
                    ? Optional.of(model) : Optional.empty();
        }

        @Override
        public Optional<ModelProfileVersion> findPublished(String tenantId, String workspaceId,
                                                            String profileId) {
            return model.getModelProfileId().equals(profileId) ? Optional.of(model) : Optional.empty();
        }

        @Override
        public List<ModelProfileVersion> findVersions(String tenantId, String workspaceId, String profileId,
                                                      int offset, int limit) {
            return findPublished(tenantId, workspaceId, profileId)
                    .map(Collections::singletonList).orElse(Collections.emptyList());
        }

        @Override
        public int nextVersion(String tenantId, String workspaceId, String profileId) {
            throw new AssertionError("model mutation is not expected in a runtime test");
        }

        @Override
        public ModelProfileVersion create(ModelProfileVersion version, String actorId) {
            throw new AssertionError("model mutation is not expected in a runtime test");
        }

        @Override
        public ModelProfileVersion publish(String tenantId, String workspaceId, String profileId, int version,
                                           String actorId) {
            throw new AssertionError("model mutation is not expected in a runtime test");
        }
    }

    private static final class PassThroughNodeExecutor implements NodeExecutor {
        private final Set<WorkflowNodeType> supported;

        private PassThroughNodeExecutor(Set<WorkflowNodeType> excluded) {
            EnumSet<WorkflowNodeType> values = EnumSet.allOf(WorkflowNodeType.class);
            values.removeAll(excluded);
            supported = Collections.unmodifiableSet(values);
        }

        @Override
        public Set<WorkflowNodeType> supportedTypes() {
            return supported;
        }

        @Override
        public NodeExecutionResult execute(NodeExecutionContext context) {
            return NodeExecutionResult.success(MAPPER.createObjectNode(), null, Collections.emptyMap());
        }
    }
}
