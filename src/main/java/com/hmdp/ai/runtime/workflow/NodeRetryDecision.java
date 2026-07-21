package com.hmdp.ai.runtime.workflow;

public final class NodeRetryDecision {
    private final boolean retry;
    private final long backoffMs;

    private NodeRetryDecision(boolean retry, long backoffMs) {
        this.retry = retry;
        this.backoffMs = backoffMs;
    }

    public static NodeRetryDecision retryAfter(long backoffMs) {
        return new NodeRetryDecision(true, Math.max(0, backoffMs));
    }

    public static NodeRetryDecision stop() {
        return new NodeRetryDecision(false, 0);
    }

    public boolean shouldRetry() { return retry; }
    public long getBackoffMs() { return backoffMs; }
}
