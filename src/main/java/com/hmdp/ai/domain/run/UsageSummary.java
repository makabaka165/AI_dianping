package com.hmdp.ai.domain.run;

public final class UsageSummary {
    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
    private final int modelCalls;
    private final int toolCalls;
    private final int retrievalCalls;
    private final long durationMs;

    public UsageSummary(long inputTokens, long outputTokens, int modelCalls, int toolCalls,
                        int retrievalCalls, long durationMs) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = inputTokens + outputTokens;
        this.modelCalls = modelCalls;
        this.toolCalls = toolCalls;
        this.retrievalCalls = retrievalCalls;
        this.durationMs = durationMs;
    }

    public static UsageSummary empty(long durationMs) {
        return new UsageSummary(0, 0, 0, 0, 0, durationMs);
    }

    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getTotalTokens() { return totalTokens; }
    public int getModelCalls() { return modelCalls; }
    public int getToolCalls() { return toolCalls; }
    public int getRetrievalCalls() { return retrievalCalls; }
    public long getDurationMs() { return durationMs; }
}
