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
import java.util.Locale;

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
            if (!duplicate) accumulate(configuration, context, updates, prefix + ".accumulator");
            appendDeduplicationValue(configuration.path("deduplicationKey").asText(""), context, seenKey,
                    updates);
            return NodeExecutionResult.success(new IntNode(iteration), Collections.singletonList(next), updates);
        } catch (Exception e) {
            return NodeExecutionResult.failure("LOOP_CONFIG_INVALID", false);
        }
    }

    private void accumulate(JsonNode configuration, NodeExecutionContext context,
                            Map<String, Object> updates, String defaultVariable) {
        String strategy = configuration.path("accumulatorStrategy").asText("REPLACE")
                .toUpperCase(Locale.ROOT);
        String accumulatorVariable = configuration.path("accumulatorVariable").asText(defaultVariable);
        String valueVariable = configuration.path("valueVariable").asText("loopValue");
        Object incoming = context.getVariables().get(valueVariable);
        if (incoming == null) return;
        Object existing = context.getVariables().get(accumulatorVariable);
        switch (strategy) {
            case "APPEND":
                List<Object> values = new ArrayList<>();
                if (existing != null && !(existing instanceof Collection)) {
                    throw new IllegalArgumentException("LOOP_APPEND_COLLECTION_REQUIRED");
                }
                if (existing instanceof Collection) values.addAll((Collection<?>) existing);
                values.add(incoming);
                updates.put(accumulatorVariable, values);
                break;
            case "MERGE":
                if (!(incoming instanceof Map)) throw new IllegalArgumentException("LOOP_MERGE_OBJECT_REQUIRED");
                if (existing != null && !(existing instanceof Map)) {
                    throw new IllegalArgumentException("LOOP_MERGE_OBJECT_REQUIRED");
                }
                Map<Object, Object> merged = new LinkedHashMap<>();
                if (existing instanceof Map) merged.putAll((Map<?, ?>) existing);
                if (incoming instanceof Map) merged.putAll((Map<?, ?>) incoming);
                updates.put(accumulatorVariable, merged);
                break;
            case "SUM": updates.put(accumulatorVariable, number(existing).add(number(incoming))); break;
            case "MIN": updates.put(accumulatorVariable, existing == null || number(incoming).compareTo(number(existing)) < 0 ? incoming : existing); break;
            case "MAX": updates.put(accumulatorVariable, existing == null || number(incoming).compareTo(number(existing)) > 0 ? incoming : existing); break;
            case "REPLACE": updates.put(accumulatorVariable, incoming); break;
            default: throw new IllegalArgumentException("LOOP_ACCUMULATOR_UNSUPPORTED");
        }
    }

    private java.math.BigDecimal number(Object value) {
        if (value == null) return java.math.BigDecimal.ZERO;
        if (!(value instanceof Number)) throw new IllegalArgumentException("LOOP_NUMBER_REQUIRED");
        return new java.math.BigDecimal(String.valueOf(value));
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
