package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

final class EvaluationTargetOutput {
    private final JsonNode actual;
    private final long inputTokens;
    private final long outputTokens;
    private final int modelCalls;
    private final int toolCalls;
    private final BigDecimal cost;
    private final boolean success;
    private final String errorCode;
    private final String errorMessage;

    EvaluationTargetOutput(JsonNode actual, long inputTokens, long outputTokens, int modelCalls,
                           int toolCalls, BigDecimal cost, boolean success,
                           String errorCode, String errorMessage) {
        this.actual = actual;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.modelCalls = modelCalls;
        this.toolCalls = toolCalls;
        this.cost = cost == null ? BigDecimal.ZERO : cost;
        this.success = success;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    static EvaluationTargetOutput success(JsonNode actual, long inputTokens, long outputTokens,
                                          int modelCalls, int toolCalls, BigDecimal cost) {
        return new EvaluationTargetOutput(actual, inputTokens, outputTokens, modelCalls, toolCalls,
                cost, true, null, null);
    }

    static EvaluationTargetOutput failure(JsonNode actual, int toolCalls,
                                          String errorCode, String errorMessage) {
        return new EvaluationTargetOutput(actual, 0, 0, 0, toolCalls, BigDecimal.ZERO,
                false, errorCode, errorMessage);
    }

    JsonNode getActual() { return actual; }
    long getInputTokens() { return inputTokens; }
    long getOutputTokens() { return outputTokens; }
    int getModelCalls() { return modelCalls; }
    int getToolCalls() { return toolCalls; }
    BigDecimal getCost() { return cost; }
    boolean isSuccess() { return success; }
    String getErrorCode() { return errorCode; }
    String getErrorMessage() { return errorMessage; }
}
