package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.hmdp.ai.domain.workflow.ConditionDslEvaluator;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class LoopNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;
    private final ConditionDslEvaluator evaluator;

    public LoopNodeExecutor(ObjectMapper mapper, ConditionDslEvaluator evaluator) {
        this.mapper = mapper;
        this.evaluator = evaluator;
    }

    @Override
    public Set<WorkflowNodeType> supportedTypes() {
        return Collections.singleton(WorkflowNodeType.LOOP);
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        try {
            JsonNode configuration = mapper.readTree(context.getNode().getConfigurationJson());
            int configuredMaximum = configuration.path("maxIterations").asInt(0);
            int maximum = Math.min(configuredMaximum,
                    context.getExecutionContext().getExecutionBudget().getMaxLoopIterations());
            String prefix = "loop." + context.getNode().getCode();
            String iterationKey = prefix + ".iteration";
            String startedKey = prefix + ".startedAtEpochMs";
            String seenKey = prefix + ".seen";
            int iteration = ((Number) context.getVariables().getOrDefault(iterationKey, 0)).intValue();
            long now = Instant.now().toEpochMilli();
            long started = ((Number) context.getVariables().getOrDefault(startedKey, now)).longValue();
            long timeoutMs = configuration.path("perIterationTimeoutMs")
                    .asLong(context.getNode().getTimeoutMs());
            if (iteration > 0 && now - started > timeoutMs) {
                return NodeExecutionResult.failure("LOOP_ITERATION_TIMEOUT", true);
            }

            boolean done = evaluator.evaluate(configuration.path("terminationCondition").toString(),
                    context.getVariables());
            boolean duplicate = duplicate(configuration.path("deduplicationKey").asText(""), context, seenKey);
            if (duplicate) done = true;
            if (!done && iteration >= maximum) {
                return NodeExecutionResult.failure("LOOP_ITERATION_LIMIT_EXCEEDED", false);
            }
            String label = done ? "exit" : "body";
            String next = context.getOutgoingEdges().stream()
                    .filter(edge -> label.equalsIgnoreCase(edge.getLabel()))
                    .map(WorkflowEdgeDefinition::getTargetNodeCode).findFirst().orElse(null);
            if (next == null) return NodeExecutionResult.failure("LOOP_EDGE_REQUIRED", false);

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(iterationKey, done ? iteration : iteration + 1);
            if (!done) updates.put(startedKey, now);
            appendDeduplicationValue(configuration.path("deduplicationKey").asText(""), context, seenKey,
                    updates);
            return NodeExecutionResult.success(new IntNode(iteration), Collections.singletonList(next), updates);
        } catch (Exception e) {
            return NodeExecutionResult.failure("LOOP_CONFIG_INVALID", false);
        }
    }

    private boolean duplicate(String variable, NodeExecutionContext context, String seenKey) {
        if (variable == null || variable.trim().isEmpty()) return false;
        Object value = context.getVariables().get(variable);
        Object seen = context.getVariables().get(seenKey);
        return value != null && seen instanceof Collection && ((Collection<?>) seen).contains(value);
    }

    private void appendDeduplicationValue(String variable, NodeExecutionContext context, String seenKey,
                                          Map<String, Object> updates) {
        if (variable == null || variable.trim().isEmpty()) return;
        Object value = context.getVariables().get(variable);
        if (value == null) return;
        List<Object> seen = new ArrayList<>();
        Object existing = context.getVariables().get(seenKey);
        if (existing instanceof Collection) seen.addAll((Collection<?>) existing);
        if (!seen.contains(value)) seen.add(value);
        updates.put(seenKey, seen);
    }
}
