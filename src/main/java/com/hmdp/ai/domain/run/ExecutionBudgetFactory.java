package com.hmdp.ai.domain.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ExecutionBudgetFactory {
    private final ObjectMapper objectMapper;

    public ExecutionBudgetFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ExecutionBudget fromPolicy(String policyJson) {
        ExecutionBudget defaults = ExecutionBudget.defaults();
        try {
            JsonNode policy = objectMapper.readTree(policyJson);
            return new ExecutionBudget(
                    positive(policy, "maxWorkflowNodes", defaults.getMaxWorkflowNodes()),
                    positive(policy, "maxLoopIterations", defaults.getMaxLoopIterations()),
                    positive(policy, "maxParallelism", defaults.getMaxParallelism()),
                    positive(policy, "maxModelCalls", defaults.getMaxModelCalls()),
                    positive(policy, "maxToolCalls", defaults.getMaxToolCalls()),
                    positive(policy, "maxExternalCalls", defaults.getMaxExternalCalls()),
                    positiveLong(policy, "maxInputTokens", defaults.getMaxInputTokens()),
                    positiveLong(policy, "maxOutputTokens", defaults.getMaxOutputTokens()),
                    positiveLong(policy, "maxTotalTokens", defaults.getMaxTotalTokens()),
                    Duration.ofSeconds(positiveLong(policy, "maxRunDurationSeconds",
                            defaults.getMaxRunDuration().getSeconds())),
                    positiveLong(policy, "maxArtifactBytes", defaults.getMaxArtifactBytes()));
        } catch (Exception e) {
            throw new IllegalArgumentException("executionPolicyJson is invalid", e);
        }
    }

    public ExecutionBudget fromStoredJson(String json) {
        return fromPolicy(json);
    }

    public String snapshotJson(ExecutionBudget budget) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("maxWorkflowNodes", budget.getMaxWorkflowNodes());
        snapshot.put("maxLoopIterations", budget.getMaxLoopIterations());
        snapshot.put("maxParallelism", budget.getMaxParallelism());
        snapshot.put("maxModelCalls", budget.getMaxModelCalls());
        snapshot.put("maxToolCalls", budget.getMaxToolCalls());
        snapshot.put("maxExternalCalls", budget.getMaxExternalCalls());
        snapshot.put("maxInputTokens", budget.getMaxInputTokens());
        snapshot.put("maxOutputTokens", budget.getMaxOutputTokens());
        snapshot.put("maxTotalTokens", budget.getMaxTotalTokens());
        snapshot.put("maxRunDurationSeconds", budget.getMaxRunDuration().getSeconds());
        snapshot.put("maxArtifactBytes", budget.getMaxArtifactBytes());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("execution budget cannot be serialized", e);
        }
    }

    private int positive(JsonNode node, String field, int fallback) {
        long value = positiveLong(node, field, fallback);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException(field + " is too large");
        return (int) value;
    }

    private long positiveLong(JsonNode node, String field, long fallback) {
        if (!node.has(field)) return fallback;
        long value = node.get(field).asLong(-1);
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }
}
