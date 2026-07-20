package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

public final class EvaluationExecutionResult {
    private final String runId;
    private final JsonNode actual;
    private final long latencyMs;
    private final long inputTokens;
    private final long outputTokens;
    private final int modelCalls;
    private final int toolCalls;
    private final double cost;
    private final boolean success;

    public EvaluationExecutionResult(String runId, JsonNode actual, long latencyMs, long inputTokens,
                                    long outputTokens, int modelCalls, int toolCalls, double cost,
                                    boolean success) {
        this.runId = runId;
        this.actual = actual;
        this.latencyMs = latencyMs;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.modelCalls = modelCalls;
        this.toolCalls = toolCalls;
        this.cost = cost;
        this.success = success;
    }

    public String getRunId() { return runId; }
    public JsonNode getActual() { return actual; }
    public long getLatencyMs() { return latencyMs; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public int getModelCalls() { return modelCalls; }
    public int getToolCalls() { return toolCalls; }
    public double getCost() { return cost; }
    public boolean isSuccess() { return success; }
}
