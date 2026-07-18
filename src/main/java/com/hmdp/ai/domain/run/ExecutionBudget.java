package com.hmdp.ai.domain.run;

import java.time.Duration;

public final class ExecutionBudget {
    private final int maxWorkflowNodes;
    private final int maxLoopIterations;
    private final int maxParallelism;
    private final int maxModelCalls;
    private final int maxToolCalls;
    private final int maxExternalCalls;
    private final long maxInputTokens;
    private final long maxOutputTokens;
    private final long maxTotalTokens;
    private final Duration maxRunDuration;
    private final long maxArtifactBytes;

    public ExecutionBudget(int maxWorkflowNodes, int maxLoopIterations, int maxParallelism,
                           int maxModelCalls, int maxToolCalls, int maxExternalCalls,
                           long maxInputTokens, long maxOutputTokens, long maxTotalTokens,
                           Duration maxRunDuration, long maxArtifactBytes) {
        if (maxWorkflowNodes <= 0 || maxLoopIterations <= 0 || maxParallelism <= 0
                || maxModelCalls <= 0 || maxToolCalls <= 0 || maxExternalCalls <= 0
                || maxInputTokens <= 0 || maxOutputTokens <= 0 || maxTotalTokens <= 0
                || maxRunDuration == null || maxRunDuration.isZero() || maxRunDuration.isNegative()
                || maxArtifactBytes <= 0) {
            throw new IllegalArgumentException("execution budget values must be positive");
        }
        this.maxWorkflowNodes = maxWorkflowNodes;
        this.maxLoopIterations = maxLoopIterations;
        this.maxParallelism = maxParallelism;
        this.maxModelCalls = maxModelCalls;
        this.maxToolCalls = maxToolCalls;
        this.maxExternalCalls = maxExternalCalls;
        this.maxInputTokens = maxInputTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.maxTotalTokens = maxTotalTokens;
        this.maxRunDuration = maxRunDuration;
        this.maxArtifactBytes = maxArtifactBytes;
    }

    public static ExecutionBudget defaults() {
        return new ExecutionBudget(64, 5, 4, 8, 16, 8,
                32000, 8000, 40000, Duration.ofMinutes(2), 20L * 1024L * 1024L);
    }

    public int getMaxWorkflowNodes() { return maxWorkflowNodes; }
    public int getMaxLoopIterations() { return maxLoopIterations; }
    public int getMaxParallelism() { return maxParallelism; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public int getMaxExternalCalls() { return maxExternalCalls; }
    public long getMaxInputTokens() { return maxInputTokens; }
    public long getMaxOutputTokens() { return maxOutputTokens; }
    public long getMaxTotalTokens() { return maxTotalTokens; }
    public Duration getMaxRunDuration() { return maxRunDuration; }
    public long getMaxArtifactBytes() { return maxArtifactBytes; }
}
