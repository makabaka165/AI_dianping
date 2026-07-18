package com.hmdp.ai.domain.run;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class UsageSummary {
    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
    private final int modelCalls;
    private final int toolCalls;
    private final int retrievalCalls;
    private final long durationMs;

    @JsonCreator
    public UsageSummary(@JsonProperty("inputTokens") long inputTokens,
                        @JsonProperty("outputTokens") long outputTokens,
                        @JsonProperty("modelCalls") int modelCalls,
                        @JsonProperty("toolCalls") int toolCalls,
                        @JsonProperty("retrievalCalls") int retrievalCalls,
                        @JsonProperty("durationMs") long durationMs) {
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
