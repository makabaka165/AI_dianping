package com.hmdp.ai.runtime.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.NodeRunClaim;
import com.hmdp.ai.domain.run.NodeRunRepository;
import com.hmdp.ai.domain.run.NodeRunStatus;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.workflow.ConditionDslEvaluator;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.domain.workflow.WorkflowState;
import com.hmdp.ai.domain.workflow.WorkflowStateRepository;
import com.hmdp.ai.domain.workflow.WorkflowStateStatus;
import com.hmdp.ai.runtime.node.NodeExecutionContext;
import com.hmdp.ai.runtime.node.NodeExecutionResult;
import com.hmdp.ai.runtime.node.WorkflowNodeRegistry;
import com.hmdp.ai.runtime.cancellation.CancellationToken;
import com.hmdp.ai.runtime.cancellation.NodeCancellationToken;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import com.hmdp.ai.shared.json.ContentHashService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class DefaultWorkflowRuntime implements WorkflowRuntime {
    private static final int MAX_FOREACH_ITEMS = 1000;
    private static final Duration DEFAULT_WAIT_TTL = Duration.ofHours(24);

    private final WorkflowNodeRegistry registry;
    private final NodeRunRepository nodeRuns;
    private final WorkflowStateRepository states;
    private final ObjectMapper mapper;
    private final NodeReliabilityExecutor nodeReliability;
    private final AsyncTaskExecutor branchExecutor;
    private final ConditionDslEvaluator conditions;
    private final ContentHashService hashes;
    private final WorkflowPauseCoordinator pauseCoordinator;
    private final SecureRandom secureRandom = new SecureRandom();
    private final RunCancellationRegistry cancellations;

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultWorkflowRuntime(WorkflowNodeRegistry registry, NodeRunRepository nodeRuns,
                                  WorkflowStateRepository states, ObjectMapper mapper,
                                  NodeReliabilityExecutor nodeReliability,
                                  @Qualifier("workflowBranchExecutor") AsyncTaskExecutor branchExecutor,
                                  ConditionDslEvaluator conditions, ContentHashService hashes,
                                  WorkflowPauseCoordinator pauseCoordinator,
                                  RunCancellationRegistry cancellations) {
        this.registry = registry;
        this.nodeRuns = nodeRuns;
        this.states = states;
        this.mapper = mapper;
        this.nodeReliability = nodeReliability;
        this.branchExecutor = branchExecutor;
        this.conditions = conditions;
        this.hashes = hashes;
        this.pauseCoordinator = pauseCoordinator;
        this.cancellations = cancellations;
    }

    public DefaultWorkflowRuntime(WorkflowNodeRegistry registry, NodeRunRepository nodeRuns,
                                  WorkflowStateRepository states, ObjectMapper mapper,
                                  ThreadPoolTaskExecutor executor, ConditionDslEvaluator conditions,
                                  ContentHashService hashes, WorkflowPauseCoordinator pauseCoordinator) {
        this(registry, nodeRuns, states, mapper, executor,
                new TaskExecutorAdapter(ForkJoinPool.commonPool()), conditions, hashes, pauseCoordinator, null);
    }

    private DefaultWorkflowRuntime(WorkflowNodeRegistry registry, NodeRunRepository nodeRuns,
                                   WorkflowStateRepository states, ObjectMapper mapper,
                                   ThreadPoolTaskExecutor executor, AsyncTaskExecutor branchExecutor,
                                   ConditionDslEvaluator conditions, ContentHashService hashes,
                                   WorkflowPauseCoordinator pauseCoordinator,
                                   RunCancellationRegistry cancellations) {
        this(registry, nodeRuns, states, mapper,
                new NodeReliabilityExecutor(executor, cancellations, mapper), branchExecutor, conditions,
                hashes, pauseCoordinator, cancellations);
    }

    @Override
    public AgentRunOutput execute(WorkflowDefinition workflow, PublishedAgentDefinition agent,
                                  ExecutionContext context, AgentInputRequest input) {
        Graph graph = new Graph(workflow);
        WorkflowState state = loadOrCreateState(workflow, context, input, graph.startNode);
        if (!workflow.getId().equals(state.getWorkflowVersionId())) {
            throw new IllegalStateException("persisted workflow version does not match run snapshot");
        }

        ArrayDeque<String> queue = new ArrayDeque<>(state.getCurrentNodeCodes());
        Map<String, Object> variables = new LinkedHashMap<>(state.getVariables());
        Set<String> completed = new LinkedHashSet<>(state.getCompletedNodeKeys());
        Map<String, Integer> counts = new LinkedHashMap<>(state.getExecutionCounts());
        int executions = counts.values().stream().mapToInt(Integer::intValue).sum();

        while (!queue.isEmpty()) {
            CancellationToken token = cancellations == null ? null : cancellations.token(context.getRunId());
            if (token != null) token.throwIfCancelled();
            enforceBudget(context, ++executions);
            String code = queue.removeFirst();
            WorkflowNodeDefinition node = graph.requireNode(code);
            if ((node.getType() == WorkflowNodeType.JOIN || node.getType() == WorkflowNodeType.END)
                    && completed.contains(code)) {
                continue;
            }

            NodeOutcome outcome = executeNode(workflow, agent, context, node, variables,
                    graph.outgoing(code), counts, "main");
            variables.putAll(outcome.result.getVariableUpdates());
            completed.add(code);

            if (outcome.result.getStatus() == NodeRunStatus.WAITING) {
                List<String> continuation = outcome.next;
                WorkflowState waitingState = state.progress(continuation, variables, completed, counts);
                pause(context, node, outcome, waitingState);
            }

            if (node.getType() == WorkflowNodeType.PARALLEL) {
                String join = graph.findConvergenceJoin(outcome.next);
                ParallelResult result = executeParallelBranches(workflow, agent, context, graph, node,
                        outcome.next, join, variables, completed, counts);
                variables.clear();
                variables.putAll(result.variables);
                completed.addAll(result.completed);
                counts.clear();
                counts.putAll(result.counts);
                queue.addLast(join);
            } else if (node.getType() == WorkflowNodeType.FOREACH) {
                String continuation = executeForEach(workflow, agent, context, graph, node, variables,
                        completed, counts);
                queue.addLast(continuation);
            } else {
                for (String next : outcome.next) queue.addLast(next);
            }

            state = states.saveProgress(state.progress(new ArrayList<>(queue), variables, completed, counts),
                    context.getUserId());
            if (node.getType() == WorkflowNodeType.END) break;
        }

        Object output = variables.get("agentOutput");
        AgentRunOutput result = convertOutput(output);
        states.complete(context.getTenantId(), context.getWorkspaceId(), context.getRunId(), context.getUserId());
        return result;
    }

    private WorkflowState loadOrCreateState(WorkflowDefinition workflow, ExecutionContext context,
                                            AgentInputRequest input, String startNode) {
        Optional<WorkflowState> existing = states.find(context.getTenantId(), context.getWorkspaceId(),
                context.getRunId());
        if (existing.isPresent()) return existing.get();
        Map<String, Object> variables = new LinkedHashMap<>(context.getVariables());
        variables.put("input", mapper.convertValue(input, new TypeReference<Map<String, Object>>() {}));
        variables.put("text", input.getText());
        WorkflowState initial = new WorkflowState(context.getTenantId(), context.getWorkspaceId(),
                context.getRunId(), workflow.getId(), Collections.singletonList(startNode), variables,
                Collections.emptySet(), Collections.emptyMap(), null, WorkflowStateStatus.RUNNING, null, 0);
        return states.create(initial, context.getUserId());
    }

    private ParallelResult executeParallelBranches(WorkflowDefinition workflow, PublishedAgentDefinition agent,
                                                   ExecutionContext context, Graph graph,
                                                   WorkflowNodeDefinition parallelNode, List<String> starts,
                                                   String join, Map<String, Object> variables,
                                                   Set<String> completed, Map<String, Integer> counts) {
        JsonNode configuration = readConfiguration(parallelNode);
        int maximum = boundedParallelism(configuration.path("maxParallelism").asInt(
                context.getExecutionBudget().getMaxParallelism()), context, starts.size());
        long timeoutMs = positive(configuration.path("branchTimeoutMs").asLong(parallelNode.getTimeoutMs()),
                parallelNode.getTimeoutMs());
        boolean partialSuccess = "PARTIAL_SUCCESS".equalsIgnoreCase(
                configuration.path("failurePolicy").asText("FAIL_FAST"));
        List<Future<BranchResult>> active = new ArrayList<>();
        List<BranchResult> ordered = new ArrayList<>();

        for (int offset = 0; offset < starts.size(); offset += maximum) {
            active.clear();
            int end = Math.min(starts.size(), offset + maximum);
            for (int index = offset; index < end; index++) {
                final int branchIndex = index;
                final String branchStart = starts.get(index);
                Future<BranchResult> future = branchExecutor.submit(() -> executeBranch(workflow, agent, context,
                        graph, branchStart,
                        join, new LinkedHashMap<>(variables), new LinkedHashSet<>(),
                        new LinkedHashMap<>(counts), "parallel:" + parallelNode.getCode() + ':' + branchIndex,
                        Instant.now().plusMillis(timeoutMs)));
                active.add(future);
                track(context.getRunId(), future);
            }
            for (Future<BranchResult> future : active) {
                try {
                    ordered.add(future.get(timeoutMs, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    future.cancel(true);
                    throwIfRunCancelled(context.getRunId(), e);
                    if (!partialSuccess) {
                        active.forEach(item -> item.cancel(true));
                        throw new IllegalStateException("parallel workflow branch failed", e);
                    }
                    ordered.add(BranchResult.failed(e));
                } finally {
                    untrack(context.getRunId(), future);
                }
            }
        }

        Map<String, Object> merged = mergeParallelVariables(parallelNode.getCode(), variables, ordered);
        Set<String> mergedCompleted = new LinkedHashSet<>(completed);
        Map<String, Integer> mergedCounts = new LinkedHashMap<>(counts);
        for (BranchResult result : ordered) {
            mergedCompleted.addAll(result.completed);
            result.counts.forEach((key, value) -> mergedCounts.merge(key, value, Math::max));
        }
        return new ParallelResult(merged, mergedCompleted, mergedCounts);
    }

    private String executeForEach(WorkflowDefinition workflow, PublishedAgentDefinition agent,
                                  ExecutionContext context, Graph graph, WorkflowNodeDefinition node,
                                  Map<String, Object> variables, Set<String> completed,
                                  Map<String, Integer> counts) {
        JsonNode configuration = readConfiguration(node);
        String collectionVariable = requiredText(configuration, "collectionVariable");
        Object raw = variables.get(collectionVariable);
        if (!(raw instanceof Collection)) throw new IllegalStateException("FOREACH_COLLECTION_REQUIRED");
        List<?> items = new ArrayList<>((Collection<?>) raw);
        if (items.size() > MAX_FOREACH_ITEMS) throw new IllegalStateException("FOREACH_ITEM_LIMIT_EXCEEDED");
        String itemVariable = configuration.path("itemVariable").asText("item");
        String indexVariable = configuration.path("indexVariable").asText("index");
        String resultVariable = configuration.path("resultVariable").asText(node.getCode() + ".results");
        String body = graph.edgeTarget(node.getCode(), "body").orElseGet(() ->
                graph.outgoing(node.getCode()).stream().map(WorkflowEdgeDefinition::getTargetNodeCode)
                        .findFirst().orElseThrow(() -> new IllegalStateException("FOREACH_BODY_REQUIRED")));
        String join = graph.findFirstReachableJoin(body);
        int maximum = boundedParallelism(configuration.path("maxParallelism").asInt(
                context.getExecutionBudget().getMaxParallelism()), context, Math.max(items.size(), 1));
        long timeoutMs = positive(configuration.path("branchTimeoutMs").asLong(node.getTimeoutMs()),
                node.getTimeoutMs());
        boolean partialSuccess = "PARTIAL_SUCCESS".equalsIgnoreCase(
                configuration.path("failurePolicy").asText("FAIL_FAST"));
        List<Object> results = new ArrayList<>(Collections.nCopies(items.size(), null));

        for (int offset = 0; offset < items.size(); offset += maximum) {
            List<Future<BranchResult>> futures = new ArrayList<>();
            int end = Math.min(items.size(), offset + maximum);
            for (int index = offset; index < end; index++) {
                Map<String, Object> branchVariables = new LinkedHashMap<>(variables);
                branchVariables.put(itemVariable, items.get(index));
                branchVariables.put(indexVariable, index);
                final int itemIndex = index;
                Future<BranchResult> future = branchExecutor.submit(() -> executeBranch(
                        workflow, agent, context, graph, body, join,
                        branchVariables, new LinkedHashSet<>(), new LinkedHashMap<>(counts),
                        "foreach:" + node.getCode() + ':' + itemIndex, Instant.now().plusMillis(timeoutMs)));
                futures.add(future);
                track(context.getRunId(), future);
            }
            for (int local = 0; local < futures.size(); local++) {
                int itemIndex = offset + local;
                try {
                    BranchResult result = futures.get(local).get(timeoutMs, TimeUnit.MILLISECONDS);
                    Map<String, Object> delta = changedVariables(variables, result.variables);
                    delta.remove(itemVariable);
                    delta.remove(indexVariable);
                    results.set(itemIndex, delta);
                    completed.addAll(result.completed);
                    result.counts.forEach((key, value) -> counts.merge(key, value, Math::max));
                } catch (Exception e) {
                    futures.get(local).cancel(true);
                    throwIfRunCancelled(context.getRunId(), e);
                    if (!partialSuccess) {
                        futures.forEach(future -> future.cancel(true));
                        throw new IllegalStateException("foreach branch failed at index " + itemIndex, e);
                    }
                    results.set(itemIndex, Collections.singletonMap("errorCode", "FOREACH_ITEM_FAILED"));
                } finally {
                    untrack(context.getRunId(), futures.get(local));
                }
            }
        }
        variables.put(resultVariable, results);
        return join;
    }

    private BranchResult executeBranch(WorkflowDefinition workflow, PublishedAgentDefinition agent,
                                       ExecutionContext context, Graph graph, String start, String stopBefore,
                                       Map<String, Object> variables, Set<String> completed,
                                       Map<String, Integer> counts, String namespace, Instant branchDeadline) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            CancellationToken token = cancellations == null ? null : cancellations.token(context.getRunId());
            if (token != null) token.throwIfCancelled();
            if (Instant.now().isAfter(branchDeadline) || Instant.now().isAfter(context.getDeadline())) {
                throw new IllegalStateException("workflow branch timed out");
            }
            String code = queue.removeFirst();
            if (stopBefore.equals(code)) continue;
            WorkflowNodeDefinition node = graph.requireNode(code);
            if (node.getType() == WorkflowNodeType.HUMAN_FEEDBACK
                    || node.getType() == WorkflowNodeType.HUMAN_APPROVAL) {
                throw new IllegalStateException("human interaction nodes cannot execute inside a parallel branch");
            }
            NodeOutcome outcome = executeNode(workflow, agent, context, node, variables,
                    graph.outgoing(code), counts, namespace);
            if (outcome.result.getStatus() == NodeRunStatus.WAITING) {
                throw new IllegalStateException("parallel branch entered an unsupported waiting state");
            }
            variables.putAll(outcome.result.getVariableUpdates());
            completed.add(code);
            for (String next : outcome.next) if (!stopBefore.equals(next)) queue.addLast(next);
        }
        return new BranchResult(variables, completed, counts, null);
    }

    private NodeOutcome executeNode(WorkflowDefinition workflow, PublishedAgentDefinition agent,
                                    ExecutionContext context, WorkflowNodeDefinition node,
                                    Map<String, Object> variables, List<WorkflowEdgeDefinition> outgoing,
                                    Map<String, Integer> counts, String namespace) {
        String countKey = namespace + ':' + node.getCode();
        int occurrence = counts.merge(countKey, 1, Integer::sum);
        String idempotencyKey = context.getRunId() + ':' + countKey + ':' + occurrence;
        NodeRunClaim claim = nodeRuns.start(context, node.getCode(), node.getType().name(), json(variables),
                idempotencyKey);
        if (!claim.isClaimed()) return restore(claim.getOutputJson(), outgoing, variables);

        CancellationToken runToken = cancellations == null ? null : cancellations.token(context.getRunId());
        NodeCancellationToken nodeToken = new NodeCancellationToken(runToken, context.getDeadline());
        NodeExecutionContext nodeContext = new NodeExecutionContext(context, agent, workflow, node,
                Collections.unmodifiableMap(new LinkedHashMap<>(variables)), outgoing, claim.getNodeRunId(),
                nodeToken);
        NodeExecutionResult result = nodeReliability.execute(node, nodeContext,
                () -> registry.require(node.getType()).execute(nodeContext));
        List<String> next = result.getNextNodeIds().isEmpty()
                ? defaultNext(outgoing, variables, result.getVariableUpdates()) : result.getNextNodeIds();
        if (result.getStatus() == NodeRunStatus.FAILED) {
            if ("NODE_CANCELLED".equals(result.getErrorCode())) {
                nodeRuns.fail(context.getTenantId(), context.getWorkspaceId(), claim.getNodeRunId(),
                        NodeRunStatus.CANCELLED, "RUN_CANCELLED", "run was cancelled", false);
                throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
            }
            nodeRuns.fail(context.getTenantId(), context.getWorkspaceId(), claim.getNodeRunId(),
                    NodeRunStatus.FAILED, result.getErrorCode(), result.getErrorCode(), result.isRetryable());
            throw new IllegalStateException(result.getErrorCode());
        }
        PersistedNodeResult persisted = new PersistedNodeResult(result.getOutput(), result.getVariableUpdates(), next);
        if (result.getStatus() == NodeRunStatus.WAITING) {
            nodeRuns.waitForResume(context.getTenantId(), context.getWorkspaceId(), claim.getNodeRunId(),
                    json(persisted));
        } else {
            nodeRuns.complete(context.getTenantId(), context.getWorkspaceId(), claim.getNodeRunId(),
                    json(persisted), json(result.getUsage()));
        }
        return new NodeOutcome(result, next);
    }

    private void track(String runId, Future<?> future) {
        if (cancellations != null) {
            cancellations.track(runId, future);
        }
    }

    private void untrack(String runId, Future<?> future) {
        if (cancellations != null) {
            cancellations.untrack(runId, future);
        }
    }

    private void throwIfRunCancelled(String runId, Exception cause) {
        CancellationToken token = cancellations == null ? null : cancellations.token(runId);
        if ((token != null && token.isCancelled()) || cause instanceof java.util.concurrent.CancellationException) {
            throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
        }
    }

    private NodeOutcome restore(String persistedJson, List<WorkflowEdgeDefinition> outgoing,
                                Map<String, Object> variables) {
        try {
            PersistedNodeResult stored = mapper.readValue(persistedJson, PersistedNodeResult.class);
            NodeExecutionResult result = NodeExecutionResult.success(stored.output, stored.nextNodeIds,
                    stored.variableUpdates);
            List<String> next = stored.nextNodeIds == null || stored.nextNodeIds.isEmpty()
                    ? defaultNext(outgoing, variables, stored.variableUpdates) : stored.nextNodeIds;
            return new NodeOutcome(result, next);
        } catch (Exception e) {
            throw new IllegalStateException("persisted node result is invalid", e);
        }
    }

    private void pause(ExecutionContext context, WorkflowNodeDefinition node, NodeOutcome outcome,
                       WorkflowState waitingState) {
        boolean approval = node.getType() == WorkflowNodeType.HUMAN_APPROVAL
                || "TOOL_APPROVAL_REQUIRED".equals(outcome.result.getErrorCode());
        RunStatus runStatus = approval
                ? RunStatus.WAITING_FOR_APPROVAL : RunStatus.WAITING_FOR_USER;
        WorkflowStateStatus stateStatus = approval
                ? WorkflowStateStatus.WAITING_FOR_APPROVAL : WorkflowStateStatus.WAITING_FOR_USER;
        JsonNode configuration = readConfiguration(node);
        long ttlSeconds = positive(configuration.path("waitTtlSeconds").asLong(DEFAULT_WAIT_TTL.getSeconds()),
                DEFAULT_WAIT_TTL.getSeconds());
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        String token = newResumeToken();
        String tokenHash = hashes.sha256(token);
        pauseCoordinator.pause(context, waitingState, stateStatus, runStatus, node.getCode(), tokenHash, expiresAt);
        throw new WorkflowPausedException(runStatus, node.getCode(), token, tokenHash, expiresAt,
                questions(configuration));
    }

    private List<String> defaultNext(List<WorkflowEdgeDefinition> outgoing, Map<String, Object> variables,
                                     Map<String, Object> updates) {
        Map<String, Object> evaluationVariables = new LinkedHashMap<>(variables);
        evaluationVariables.putAll(updates);
        return outgoing.stream().filter(edge -> conditions.evaluate(edge.getConditionJson(), evaluationVariables))
                .sorted(Comparator.comparingInt(WorkflowEdgeDefinition::getPriority).reversed())
                .map(WorkflowEdgeDefinition::getTargetNodeCode).collect(Collectors.toList());
    }

    private Map<String, Object> mergeParallelVariables(String nodeCode, Map<String, Object> base,
                                                       List<BranchResult> branches) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        List<Map<String, Object>> branchOutputs = new ArrayList<>();
        Map<String, Object> proposed = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (BranchResult branch : branches) {
            if (branch.error != null) {
                branchOutputs.add(Collections.singletonMap("errorCode", "PARALLEL_BRANCH_FAILED"));
                continue;
            }
            Map<String, Object> delta = changedVariables(base, branch.variables);
            branchOutputs.add(delta);
            delta.forEach((key, value) -> {
                if (proposed.containsKey(key) && !java.util.Objects.equals(proposed.get(key), value)) {
                    conflicts.add(key);
                } else {
                    proposed.put(key, value);
                }
            });
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("PARALLEL_VARIABLE_CONFLICT:" + String.join(",", conflicts));
        }
        merged.putAll(proposed);
        merged.put("parallel." + nodeCode + ".branches", branchOutputs);
        return merged;
    }

    private Map<String, Object> changedVariables(Map<String, Object> base, Map<String, Object> candidate) {
        Map<String, Object> changed = new LinkedHashMap<>();
        candidate.forEach((key, value) -> {
            if (!base.containsKey(key) || !java.util.Objects.equals(base.get(key), value)) changed.put(key, value);
        });
        return changed;
    }

    private AgentRunOutput convertOutput(Object output) {
        if (output instanceof AgentRunOutput) return (AgentRunOutput) output;
        if (output != null) return mapper.convertValue(output, AgentRunOutput.class);
        throw new IllegalStateException("workflow did not produce agentOutput");
    }

    private void enforceBudget(ExecutionContext context, int executions) {
        if (Instant.now().isAfter(context.getDeadline())) throw new IllegalStateException("workflow deadline exceeded");
        if (executions > context.getExecutionBudget().getMaxWorkflowNodes()) {
            throw new IllegalStateException("workflow node budget exceeded");
        }
    }

    private int boundedParallelism(int configured, ExecutionContext context, int workItems) {
        int budget = Math.max(1, context.getExecutionBudget().getMaxParallelism());
        return Math.max(1, Math.min(Math.min(configured, budget), Math.max(workItems, 1)));
    }

    private long positive(long value, long fallback) { return value > 0 ? value : fallback; }

    private String requiredText(JsonNode configuration, String field) {
        String value = configuration.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalStateException(field + " is required");
        return value;
    }

    private JsonNode readConfiguration(WorkflowNodeDefinition node) {
        try {
            return mapper.readTree(node.getConfigurationJson());
        } catch (Exception e) {
            throw new IllegalStateException("workflow node configuration is invalid: " + node.getCode(), e);
        }
    }

    private List<String> questions(JsonNode configuration) {
        List<String> values = new ArrayList<>();
        JsonNode questions = configuration.path("questions");
        if (questions.isArray()) questions.forEach(question -> values.add(question.asText()));
        String single = configuration.path("question").asText("").trim();
        if (values.isEmpty() && !single.isEmpty()) values.add(single);
        if (values.isEmpty()) values.add("请补充继续执行所需的信息。");
        return values;
    }

    private String newResumeToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("workflow value cannot be serialized", e);
        }
    }

    private static final class NodeOutcome {
        private final NodeExecutionResult result;
        private final List<String> next;

        private NodeOutcome(NodeExecutionResult result, List<String> next) {
            this.result = result;
            this.next = new ArrayList<>(next);
        }
    }

    public static final class PersistedNodeResult {
        public JsonNode output;
        public Map<String, Object> variableUpdates = Collections.emptyMap();
        public List<String> nextNodeIds = Collections.emptyList();

        public PersistedNodeResult() { }

        private PersistedNodeResult(JsonNode output, Map<String, Object> variableUpdates,
                                    List<String> nextNodeIds) {
            this.output = output;
            this.variableUpdates = new LinkedHashMap<>(variableUpdates);
            this.nextNodeIds = new ArrayList<>(nextNodeIds);
        }
    }

    private static final class BranchResult {
        private final Map<String, Object> variables;
        private final Set<String> completed;
        private final Map<String, Integer> counts;
        private final Exception error;

        private BranchResult(Map<String, Object> variables, Set<String> completed,
                             Map<String, Integer> counts, Exception error) {
            this.variables = variables;
            this.completed = completed;
            this.counts = counts;
            this.error = error;
        }

        private static BranchResult failed(Exception error) {
            return new BranchResult(Collections.emptyMap(), Collections.emptySet(), Collections.emptyMap(), error);
        }
    }

    private static final class ParallelResult {
        private final Map<String, Object> variables;
        private final Set<String> completed;
        private final Map<String, Integer> counts;

        private ParallelResult(Map<String, Object> variables, Set<String> completed,
                               Map<String, Integer> counts) {
            this.variables = variables;
            this.completed = completed;
            this.counts = counts;
        }
    }

    private final class Graph {
        private final Map<String, WorkflowNodeDefinition> nodes;
        private final Map<String, List<WorkflowEdgeDefinition>> outgoing;
        private final String startNode;

        private Graph(WorkflowDefinition workflow) {
            this.nodes = workflow.getNodes().stream().collect(Collectors.toMap(WorkflowNodeDefinition::getCode,
                    node -> node));
            this.outgoing = workflow.getEdges().stream().collect(Collectors.groupingBy(
                    WorkflowEdgeDefinition::getSourceNodeCode, LinkedHashMap::new, Collectors.toList()));
            this.outgoing.values().forEach(edges -> edges.sort(Comparator.comparingInt(
                    WorkflowEdgeDefinition::getPriority).reversed()));
            this.startNode = workflow.getNodes().stream().filter(node -> node.getType() == WorkflowNodeType.START)
                    .map(WorkflowNodeDefinition::getCode).findFirst().orElseThrow(() ->
                            new IllegalStateException("workflow START missing"));
        }

        private WorkflowNodeDefinition requireNode(String code) {
            WorkflowNodeDefinition node = nodes.get(code);
            if (node == null) throw new IllegalStateException("workflow node missing: " + code);
            return node;
        }

        private List<WorkflowEdgeDefinition> outgoing(String code) {
            return outgoing.getOrDefault(code, Collections.emptyList());
        }

        private Optional<String> edgeTarget(String source, String label) {
            return outgoing(source).stream().filter(edge -> label.equalsIgnoreCase(edge.getLabel()))
                    .map(WorkflowEdgeDefinition::getTargetNodeCode).findFirst();
        }

        private String findConvergenceJoin(List<String> branchStarts) {
            if (branchStarts.size() < 2) throw new IllegalStateException("PARALLEL_BRANCH_REQUIRED");
            Map<String, Integer> scores = new HashMap<>();
            Set<String> common = null;
            for (String start : branchStarts) {
                Map<String, Integer> distances = reachableJoinDistances(start);
                if (common == null) common = new HashSet<>(distances.keySet());
                else common.retainAll(distances.keySet());
                distances.forEach((node, distance) -> scores.merge(node, distance, Integer::sum));
            }
            if (common == null || common.isEmpty()) throw new IllegalStateException("PARALLEL_JOIN_REQUIRED");
            return common.stream().min(Comparator.<String>comparingInt(scores::get)
                            .thenComparing(Comparator.naturalOrder()))
                    .orElseThrow(() -> new IllegalStateException("PARALLEL_JOIN_REQUIRED"));
        }

        private String findFirstReachableJoin(String start) {
            return reachableJoinDistances(start).entrySet().stream()
                    .min(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey).orElseThrow(() -> new IllegalStateException("FOREACH_JOIN_REQUIRED"));
        }

        private Map<String, Integer> reachableJoinDistances(String start) {
            Map<String, Integer> distances = new LinkedHashMap<>();
            Set<String> visited = new HashSet<>();
            ArrayDeque<NodeDistance> queue = new ArrayDeque<>();
            queue.add(new NodeDistance(start, 0));
            while (!queue.isEmpty()) {
                NodeDistance current = queue.removeFirst();
                if (!visited.add(current.code)) continue;
                WorkflowNodeDefinition node = requireNode(current.code);
                if (node.getType() == WorkflowNodeType.JOIN) distances.put(current.code, current.distance);
                for (WorkflowEdgeDefinition edge : outgoing(current.code)) {
                    queue.addLast(new NodeDistance(edge.getTargetNodeCode(), current.distance + 1));
                }
            }
            return distances;
        }
    }

    private static final class NodeDistance {
        private final String code;
        private final int distance;

        private NodeDistance(String code, int distance) {
            this.code = code;
            this.distance = distance;
        }
    }
}
