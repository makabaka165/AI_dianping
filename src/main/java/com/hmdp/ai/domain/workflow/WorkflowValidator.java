package com.hmdp.ai.domain.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import com.hmdp.ai.shared.validation.ValidationIssue;
import com.hmdp.ai.shared.validation.ValidationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class WorkflowValidator {
    private static final Set<String> RESERVED_VARIABLES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "input", "executionContext", "tenantId", "workspaceId", "userId", "runId",
                    "nodeRunId", "traceId", "deadline", "authorizationContext")));

    private final JsonSchemaValidationService schemas;
    private final ConditionDslEvaluator conditions;
    private final ObjectMapper mapper;
    private final Set<WorkflowNodeType> executableTypes;

    @Autowired
    public WorkflowValidator(JsonSchemaValidationService schemas, ConditionDslEvaluator conditions,
                             ObjectMapper mapper) {
        this(schemas, conditions, mapper, EnumSet.allOf(WorkflowNodeType.class));
    }

    WorkflowValidator(JsonSchemaValidationService schemas, ConditionDslEvaluator conditions,
                      ObjectMapper mapper, Set<WorkflowNodeType> executableTypes) {
        this.schemas = schemas;
        this.conditions = conditions;
        this.mapper = mapper;
        if (executableTypes == null || executableTypes.isEmpty()) {
            throw new IllegalArgumentException("executableTypes must not be empty");
        }
        this.executableTypes = Collections.unmodifiableSet(EnumSet.copyOf(executableTypes));
    }

    public ValidationResult validate(WorkflowDefinition workflow) {
        List<ValidationIssue> issues = new ArrayList<>();
        issues.addAll(schemas.validateSchema(workflow.getInputSchema(), "inputSchema").getIssues());
        issues.addAll(schemas.validateSchema(workflow.getOutputSchema(), "outputSchema").getIssues());
        issues.addAll(schemas.validateSchema(workflow.getVariablesSchema(), "variablesSchema").getIssues());

        Map<String, WorkflowNodeDefinition> nodes = indexNodes(workflow, issues);
        List<WorkflowNodeDefinition> starts = workflow.getNodes().stream()
                .filter(node -> node.getType() == WorkflowNodeType.START)
                .collect(Collectors.toList());
        if (starts.size() != 1) {
            issues.add(issue("WORKFLOW_START_COUNT", "nodes",
                    "workflow must contain exactly one START"));
        }
        if (workflow.getNodes().stream().noneMatch(node -> node.getType() == WorkflowNodeType.END)) {
            issues.add(issue("WORKFLOW_END_REQUIRED", "nodes", "workflow requires at least one END"));
        }

        Map<String, List<WorkflowEdgeDefinition>> graph = indexEdges(workflow, nodes, issues);
        Set<String> reached = starts.size() == 1
                ? reachable(starts.get(0).getCode(), graph) : Collections.emptySet();
        if (starts.size() == 1) {
            for (String code : nodes.keySet()) {
                if (!reached.contains(code)) {
                    issues.add(issue("WORKFLOW_NODE_UNREACHABLE", code, "node is unreachable"));
                }
            }
            validateTerminalPaths(reached, nodes, graph, issues);
            validateOutputPaths(starts.get(0).getCode(), nodes, graph, issues);
        }

        detectIllegalCycles(nodes, graph, issues);
        validateExecutionPolicy(workflow, issues);
        for (WorkflowNodeDefinition node : workflow.getNodes()) {
            if (!executableTypes.contains(node.getType())) {
                issues.add(issue("WORKFLOW_NODE_TYPE_UNSUPPORTED", node.getCode(),
                        "node type has no production executor"));
            }
            if (node.getTimeoutMs() <= 0 || node.getMaxAttempts() <= 0) {
                issues.add(issue("WORKFLOW_NODE_POLICY_INVALID", node.getCode(),
                        "timeout and maxAttempts must be positive"));
            }
            validateMappings(node, issues);
            validateNodeConfiguration(node, workflow, nodes, graph, issues);
        }
        validateParallelHumanNodes(workflow, nodes, graph, issues);
        return new ValidationResult(issues);
    }

    private Map<String, WorkflowNodeDefinition> indexNodes(WorkflowDefinition workflow,
                                                            List<ValidationIssue> issues) {
        Map<String, WorkflowNodeDefinition> nodes = new LinkedHashMap<>();
        for (WorkflowNodeDefinition node : workflow.getNodes()) {
            if (node.getCode() == null || node.getCode().trim().isEmpty()) {
                issues.add(issue("WORKFLOW_NODE_CODE_REQUIRED", "nodes", "node code is required"));
                continue;
            }
            if (node.getType() == null) {
                issues.add(issue("WORKFLOW_NODE_TYPE_REQUIRED", node.getCode(), "node type is required"));
            }
            if (nodes.put(node.getCode(), node) != null) {
                issues.add(issue("WORKFLOW_NODE_DUPLICATE", node.getCode(), "node code is duplicated"));
            }
        }
        return nodes;
    }

    private Map<String, List<WorkflowEdgeDefinition>> indexEdges(WorkflowDefinition workflow,
                                                                  Map<String, WorkflowNodeDefinition> nodes,
                                                                  List<ValidationIssue> issues) {
        Map<String, List<WorkflowEdgeDefinition>> graph = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (WorkflowEdgeDefinition edge : workflow.getEdges()) {
            if (edge.getId() != null && !ids.add(edge.getId())) {
                issues.add(issue("WORKFLOW_EDGE_DUPLICATE", edge.getId(), "edge id is duplicated"));
            }
            if (!nodes.containsKey(edge.getSourceNodeCode()) || !nodes.containsKey(edge.getTargetNodeCode())) {
                issues.add(issue("WORKFLOW_EDGE_NODE_MISSING", edge.getId(),
                        "edge references a missing node"));
                continue;
            }
            graph.computeIfAbsent(edge.getSourceNodeCode(), ignored -> new ArrayList<>()).add(edge);
            if (edge.getConditionJson() != null && !edge.getConditionJson().trim().isEmpty()) {
                try {
                    conditions.evaluate(edge.getConditionJson(), Collections.emptyMap());
                } catch (Exception exception) {
                    issues.add(issue("WORKFLOW_CONDITION_INVALID", edge.getId(),
                            "edge condition is invalid"));
                }
            }
        }
        return graph;
    }

    private void validateTerminalPaths(Set<String> reached, Map<String, WorkflowNodeDefinition> nodes,
                                       Map<String, List<WorkflowEdgeDefinition>> graph,
                                       List<ValidationIssue> issues) {
        Set<String> canReachEnd = nodes.values().stream()
                .filter(node -> node.getType() == WorkflowNodeType.END)
                .map(WorkflowNodeDefinition::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, List<WorkflowEdgeDefinition>> entry : graph.entrySet()) {
                if (entry.getValue().stream().anyMatch(edge -> canReachEnd.contains(edge.getTargetNodeCode()))) {
                    changed |= canReachEnd.add(entry.getKey());
                }
            }
        } while (changed);

        for (String code : reached) {
            WorkflowNodeDefinition node = nodes.get(code);
            if (node == null) continue;
            List<WorkflowEdgeDefinition> outgoing = graph.getOrDefault(code, Collections.emptyList());
            if (node.getType() == WorkflowNodeType.END && !outgoing.isEmpty()) {
                issues.add(issue("WORKFLOW_END_OUTGOING_EDGE", code, "END must not have outgoing edges"));
            } else if (node.getType() != WorkflowNodeType.END && outgoing.isEmpty()) {
                issues.add(issue("WORKFLOW_TERMINAL_MUST_BE_END", code,
                        "every terminal path must end at END"));
            }
            if (!canReachEnd.contains(code)) {
                issues.add(issue("WORKFLOW_PATH_END_REQUIRED", code,
                        "node cannot reach an END node"));
            }
        }
    }

    private void validateOutputPaths(String start, Map<String, WorkflowNodeDefinition> nodes,
                                     Map<String, List<WorkflowEdgeDefinition>> graph,
                                     List<ValidationIssue> issues) {
        ArrayDeque<OutputState> queue = new ArrayDeque<>();
        queue.add(new OutputState(start, false));
        Set<String> visited = new HashSet<>();
        Set<String> reportedEnds = new HashSet<>();
        while (!queue.isEmpty()) {
            OutputState state = queue.remove();
            String stateKey = state.nodeCode + ":" + state.outputAvailable;
            if (!visited.add(stateKey)) continue;
            WorkflowNodeDefinition node = nodes.get(state.nodeCode);
            if (node == null) continue;
            boolean outputAvailable = state.outputAvailable || writesAgentOutput(node);
            if (node.getType() == WorkflowNodeType.END) {
                if (!outputAvailable && reportedEnds.add(node.getCode())) {
                    issues.add(issue("WORKFLOW_END_OUTPUT_REQUIRED", node.getCode(),
                            "every path to END must define agentOutput"));
                }
                continue;
            }
            for (WorkflowEdgeDefinition edge : graph.getOrDefault(node.getCode(), Collections.emptyList())) {
                queue.add(new OutputState(edge.getTargetNodeCode(), outputAvailable));
            }
        }
    }

    private boolean writesAgentOutput(WorkflowNodeDefinition node) {
        try {
            JsonNode config = mapper.readTree(node.getConfigurationJson());
            if (config != null && config.isObject()) {
                String defaultOutput = node.getType() == WorkflowNodeType.LLM ? "agentOutput" : "";
                if ("agentOutput".equals(config.path("outputVariable").asText(defaultOutput))) return true;
            }
            JsonNode outputMapping = mapper.readTree(node.getOutputMappingJson());
            return outputMapping != null && outputMapping.isObject() && outputMapping.has("agentOutput");
        } catch (Exception exception) {
            return false;
        }
    }

    private void validateExecutionPolicy(WorkflowDefinition workflow, List<ValidationIssue> issues) {
        try {
            JsonNode policy = mapper.readTree(workflow.getExecutionPolicyJson());
            if (policy == null || !policy.isObject()) {
                issues.add(issue("WORKFLOW_EXECUTION_POLICY_INVALID", "executionPolicyJson",
                        "execution policy must be an object"));
                return;
            }
            for (String field : Arrays.asList("maxWorkflowNodes", "maxLoopIterations", "maxParallelism")) {
                if (policy.has(field) && policy.path(field).asInt(0) <= 0) {
                    issues.add(issue("WORKFLOW_EXECUTION_LIMIT_INVALID", "executionPolicyJson." + field,
                            field + " must be positive"));
                }
            }
        } catch (Exception exception) {
            issues.add(issue("WORKFLOW_EXECUTION_POLICY_INVALID", "executionPolicyJson",
                    "execution policy is invalid JSON"));
        }
    }

    private void validateMappings(WorkflowNodeDefinition node, List<ValidationIssue> issues) {
        validateMappingObject(node.getInputMappingJson(), node.getCode() + ".inputMapping", issues, false);
        validateMappingObject(node.getOutputMappingJson(), node.getCode() + ".outputMapping", issues, true);
    }

    private void validateMappingObject(String json, String path, List<ValidationIssue> issues,
                                       boolean outputMapping) {
        try {
            JsonNode mapping = mapper.readTree(json);
            if (mapping == null || !mapping.isObject()) {
                issues.add(issue("WORKFLOW_MAPPING_INVALID", path, "mapping must be a JSON object"));
                return;
            }
            if (outputMapping) {
                mapping.fieldNames().forEachRemaining(variable -> {
                    if (isReservedVariable(variable)) {
                        issues.add(issue("WORKFLOW_RESERVED_VARIABLE_OVERWRITE", path + "." + variable,
                                "output mapping cannot overwrite a system variable"));
                    }
                });
            }
        } catch (Exception exception) {
            issues.add(issue("WORKFLOW_MAPPING_INVALID", path, "mapping is invalid JSON"));
        }
    }

    private boolean isReservedVariable(String variable) {
        return RESERVED_VARIABLES.contains(variable) || variable.startsWith("system.")
                || variable.startsWith("execution.");
    }

    private void validateNodeConfiguration(WorkflowNodeDefinition node, WorkflowDefinition workflow,
                                           Map<String, WorkflowNodeDefinition> nodes,
                                           Map<String, List<WorkflowEdgeDefinition>> graph,
                                           List<ValidationIssue> issues) {
        JsonNode config;
        try {
            config = mapper.readTree(node.getConfigurationJson());
        } catch (Exception exception) {
            issues.add(issue("WORKFLOW_NODE_CONFIG_INVALID", node.getCode(),
                    "node configuration is invalid JSON"));
            return;
        }
        if (config == null || !config.isObject()) {
            issues.add(issue("WORKFLOW_NODE_CONFIG_INVALID", node.getCode(),
                    "node configuration must be an object"));
            return;
        }
        validateConfiguredOutput(node, config, issues);
        switch (node.getType()) {
            case LOOP:
                validateLoop(node, config, graph, issues);
                break;
            case PARALLEL:
                validateParallel(node, config, nodes, graph, issues);
                break;
            case FOREACH:
                validateForEach(node, config, nodes, graph, issues);
                break;
            case BRANCH:
                validateBranch(node, graph, issues);
                break;
            case TOOL:
                requireAnyReference(node, config, issues, "WORKFLOW_TOOL_REFERENCE_REQUIRED",
                        "toolCode", "toolId", "toolVersionId");
                break;
            case KNOWLEDGE_RETRIEVE:
            case SEMANTIC_SEARCH:
                requireAnyReference(node, config, issues, "WORKFLOW_KNOWLEDGE_REFERENCE_REQUIRED",
                        "knowledgeBaseId", "knowledgeBaseVersionId");
                break;
            case LLM:
                if (!config.path("useAgentDefaultPrompt").asBoolean(false)
                        && config.path("promptVersionId").asText().trim().isEmpty()
                        && node.getType() == WorkflowNodeType.LLM) {
                    issues.add(issue("WORKFLOW_PROMPT_REFERENCE_REQUIRED", node.getCode(),
                            "LLM must use the agent prompt or reference a prompt version"));
                }
                break;
            default:
                break;
        }
    }

    private void validateConfiguredOutput(WorkflowNodeDefinition node, JsonNode config,
                                          List<ValidationIssue> issues) {
        if (config.has("outputVariable") && isReservedVariable(config.path("outputVariable").asText())) {
            issues.add(issue("WORKFLOW_RESERVED_VARIABLE_OVERWRITE",
                    node.getCode() + ".configuration.outputVariable",
                    "output variable cannot overwrite a system variable"));
        }
    }

    private void validateLoop(WorkflowNodeDefinition node, JsonNode config,
                              Map<String, List<WorkflowEdgeDefinition>> graph,
                              List<ValidationIssue> issues) {
        if (config.path("maxIterations").asInt(0) <= 0) {
            issues.add(issue("WORKFLOW_LOOP_LIMIT_REQUIRED", node.getCode(),
                    "LOOP requires positive maxIterations"));
        }
        if (!config.has("terminationCondition")) {
            issues.add(issue("WORKFLOW_LOOP_CONDITION_REQUIRED", node.getCode(),
                    "LOOP requires terminationCondition"));
        }
        if (config.path("perIterationTimeoutMs").asLong(0) <= 0) {
            issues.add(issue("WORKFLOW_LOOP_TIMEOUT_REQUIRED", node.getCode(),
                    "LOOP requires positive perIterationTimeoutMs"));
        }
        if (config.path("deduplicationKey").asText().trim().isEmpty()) {
            issues.add(issue("WORKFLOW_LOOP_DEDUP_REQUIRED", node.getCode(),
                    "LOOP requires deduplicationKey"));
        }
        String accumulator = config.path("accumulatorStrategy").asText().toUpperCase(java.util.Locale.ROOT);
        if (!Arrays.asList("APPEND", "MERGE", "REPLACE", "SUM", "MIN", "MAX").contains(accumulator)) {
            issues.add(issue("WORKFLOW_LOOP_ACCUMULATOR_REQUIRED", node.getCode(),
                    "LOOP requires a supported accumulatorStrategy"));
        }
        List<WorkflowEdgeDefinition> outgoing = graph.getOrDefault(node.getCode(), Collections.emptyList());
        if (outgoing.stream().noneMatch(edge -> "body".equalsIgnoreCase(edge.getLabel()))) {
            issues.add(issue("WORKFLOW_LOOP_BODY_REQUIRED", node.getCode(), "LOOP requires a body edge"));
        }
        if (outgoing.stream().noneMatch(edge -> "exit".equalsIgnoreCase(edge.getLabel()))) {
            issues.add(issue("WORKFLOW_LOOP_EXIT_REQUIRED", node.getCode(), "LOOP requires an exit edge"));
        }
    }

    private void validateParallel(WorkflowNodeDefinition node, JsonNode config,
                                  Map<String, WorkflowNodeDefinition> nodes,
                                  Map<String, List<WorkflowEdgeDefinition>> graph,
                                  List<ValidationIssue> issues) {
        List<WorkflowEdgeDefinition> branches = graph.getOrDefault(node.getCode(), Collections.emptyList());
        if (branches.size() < 2) {
            issues.add(issue("WORKFLOW_PARALLEL_BRANCH_REQUIRED", node.getCode(),
                    "PARALLEL requires at least two branches"));
        }
        if (commonReachableJoins(branches, nodes, graph).isEmpty()) {
            issues.add(issue("WORKFLOW_PARALLEL_COMMON_JOIN_REQUIRED", node.getCode(),
                    "all parallel branches must converge at the same JOIN"));
        }
        if (config.has("maxParallelism") && config.path("maxParallelism").asInt(0) <= 0) {
            issues.add(issue("WORKFLOW_PARALLEL_LIMIT_REQUIRED", node.getCode(),
                    "maxParallelism must be positive"));
        }
        if (config.has("branchTimeoutMs") && config.path("branchTimeoutMs").asLong(0) <= 0) {
            issues.add(issue("WORKFLOW_PARALLEL_TIMEOUT_REQUIRED", node.getCode(),
                    "branchTimeoutMs must be positive"));
        }
    }

    private void validateForEach(WorkflowNodeDefinition node, JsonNode config,
                                 Map<String, WorkflowNodeDefinition> nodes,
                                 Map<String, List<WorkflowEdgeDefinition>> graph,
                                 List<ValidationIssue> issues) {
        if (config.path("collectionVariable").asText().trim().isEmpty()) {
            issues.add(issue("WORKFLOW_FOREACH_COLLECTION_REQUIRED", node.getCode(),
                    "FOREACH requires collectionVariable"));
        }
        List<WorkflowEdgeDefinition> outgoing = graph.getOrDefault(node.getCode(), Collections.emptyList());
        List<WorkflowEdgeDefinition> body = outgoing.stream()
                .filter(edge -> "body".equalsIgnoreCase(edge.getLabel()))
                .collect(Collectors.toList());
        if (body.size() != 1) {
            issues.add(issue("WORKFLOW_FOREACH_BODY_REQUIRED", node.getCode(),
                    "FOREACH requires exactly one body edge"));
        } else if (commonReachableJoins(body, nodes, graph).isEmpty()) {
            issues.add(issue("WORKFLOW_FOREACH_JOIN_REQUIRED", node.getCode(),
                    "FOREACH body must reach a JOIN"));
        }
        if (outgoing.stream().noneMatch(edge -> "exit".equalsIgnoreCase(edge.getLabel()))) {
            issues.add(issue("WORKFLOW_FOREACH_EXIT_REQUIRED", node.getCode(),
                    "FOREACH requires an exit edge"));
        }
    }

    private void validateBranch(WorkflowNodeDefinition node,
                                Map<String, List<WorkflowEdgeDefinition>> graph,
                                List<ValidationIssue> issues) {
        List<WorkflowEdgeDefinition> outgoing = graph.getOrDefault(node.getCode(), Collections.emptyList());
        if (outgoing.isEmpty()) {
            issues.add(issue("WORKFLOW_BRANCH_EDGE_REQUIRED", node.getCode(),
                    "BRANCH requires outgoing edges"));
        }
        boolean hasDefault = outgoing.stream().anyMatch(edge -> "default".equalsIgnoreCase(edge.getLabel())
                || edge.getConditionJson() == null || edge.getConditionJson().trim().isEmpty());
        if (!hasDefault) {
            issues.add(issue("WORKFLOW_BRANCH_DEFAULT_REQUIRED", node.getCode(),
                    "BRANCH requires an explicit default route"));
        }
    }

    private void requireAnyReference(WorkflowNodeDefinition node, JsonNode config,
                                     List<ValidationIssue> issues, String code, String... fields) {
        for (String field : fields) {
            if (!config.path(field).asText().trim().isEmpty()) return;
        }
        issues.add(issue(code, node.getCode(), "node must reference a published resource version"));
    }

    private Set<String> commonReachableJoins(List<WorkflowEdgeDefinition> branches,
                                             Map<String, WorkflowNodeDefinition> nodes,
                                             Map<String, List<WorkflowEdgeDefinition>> graph) {
        Set<String> common = null;
        for (WorkflowEdgeDefinition branch : branches) {
            Set<String> branchJoins = reachable(branch.getTargetNodeCode(), graph).stream()
                    .filter(code -> nodes.containsKey(code) && nodes.get(code).getType() == WorkflowNodeType.JOIN)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (common == null) common = branchJoins;
            else common.retainAll(branchJoins);
        }
        return common == null ? Collections.emptySet() : common;
    }

    private void validateParallelHumanNodes(WorkflowDefinition workflow,
                                            Map<String, WorkflowNodeDefinition> nodes,
                                            Map<String, List<WorkflowEdgeDefinition>> graph,
                                            List<ValidationIssue> issues) {
        for (WorkflowNodeDefinition parallel : workflow.getNodes()) {
            if (parallel.getType() != WorkflowNodeType.PARALLEL) continue;
            List<WorkflowEdgeDefinition> branches = graph.getOrDefault(parallel.getCode(), Collections.emptyList());
            Set<String> joins = commonReachableJoins(branches, nodes, graph);
            for (WorkflowEdgeDefinition branch : branches) {
                if (containsHumanBeforeJoin(branch.getTargetNodeCode(), joins, nodes, graph)) {
                    issues.add(issue("WORKFLOW_PARALLEL_HUMAN_NODE_UNSUPPORTED", parallel.getCode(),
                            "human pause nodes are not supported inside parallel branches"));
                    break;
                }
            }
        }
    }

    private boolean containsHumanBeforeJoin(String start, Set<String> joins,
                                            Map<String, WorkflowNodeDefinition> nodes,
                                            Map<String, List<WorkflowEdgeDefinition>> graph) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String code = queue.remove();
            if (!visited.add(code) || joins.contains(code)) continue;
            WorkflowNodeDefinition node = nodes.get(code);
            if (node != null && (node.getType() == WorkflowNodeType.HUMAN_FEEDBACK
                    || node.getType() == WorkflowNodeType.HUMAN_APPROVAL)) return true;
            for (WorkflowEdgeDefinition edge : graph.getOrDefault(code, Collections.emptyList())) {
                queue.add(edge.getTargetNodeCode());
            }
        }
        return false;
    }

    private Set<String> reachable(String start, Map<String, List<WorkflowEdgeDefinition>> graph) {
        Set<String> reached = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String code = queue.remove();
            if (!reached.add(code)) continue;
            for (WorkflowEdgeDefinition edge : graph.getOrDefault(code, Collections.emptyList())) {
                queue.add(edge.getTargetNodeCode());
            }
        }
        return reached;
    }

    private void detectIllegalCycles(Map<String, WorkflowNodeDefinition> nodes,
                                     Map<String, List<WorkflowEdgeDefinition>> graph,
                                     List<ValidationIssue> issues) {
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        for (String code : nodes.keySet()) {
            detectCycle(code, nodes, graph, visited, stack, issues);
        }
    }

    private void detectCycle(String code, Map<String, WorkflowNodeDefinition> nodes,
                             Map<String, List<WorkflowEdgeDefinition>> graph,
                             Set<String> visited, Set<String> stack,
                             List<ValidationIssue> issues) {
        if (stack.contains(code)) {
            WorkflowNodeDefinition node = nodes.get(code);
            if (node == null || node.getType() != WorkflowNodeType.LOOP) {
                issues.add(issue("WORKFLOW_ILLEGAL_CYCLE", code,
                        "cycles must be expressed through LOOP nodes"));
            }
            return;
        }
        if (!visited.add(code)) return;
        stack.add(code);
        for (WorkflowEdgeDefinition edge : graph.getOrDefault(code, Collections.emptyList())) {
            detectCycle(edge.getTargetNodeCode(), nodes, graph, visited, stack, issues);
        }
        stack.remove(code);
    }

    private ValidationIssue issue(String code, String path, String message) {
        return new ValidationIssue(code, path, message);
    }

    private static final class OutputState {
        private final String nodeCode;
        private final boolean outputAvailable;

        private OutputState(String nodeCode, boolean outputAvailable) {
            this.nodeCode = nodeCode;
            this.outputAvailable = outputAvailable;
        }
    }
}
