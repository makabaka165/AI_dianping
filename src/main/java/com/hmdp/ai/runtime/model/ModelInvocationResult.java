package com.hmdp.ai.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

public final class ModelInvocationResult {
    private final String content;
    private final JsonNode structuredOutput;
    private final long inputTokens;
    private final long outputTokens;
    private final boolean estimatedUsage;
    private final long latencyMs;
    private final BigDecimal estimatedCost;
    private final String providerInvocationId;

    public ModelInvocationResult(String content, JsonNode structuredOutput, long inputTokens, long outputTokens,
                                 boolean estimatedUsage, long latencyMs, BigDecimal estimatedCost,
                                 String providerInvocationId) {
        this.content = content;
        this.structuredOutput = structuredOutput;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.estimatedUsage = estimatedUsage;
        this.latencyMs = latencyMs;
        this.estimatedCost = estimatedCost == null ? BigDecimal.ZERO : estimatedCost;
        this.providerInvocationId = providerInvocationId;
    }

    public String getContent() { return content; }
    public JsonNode getStructuredOutput() { return structuredOutput; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public boolean isEstimatedUsage() { return estimatedUsage; }
    public long getLatencyMs() { return latencyMs; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public String getProviderInvocationId() { return providerInvocationId; }
}
