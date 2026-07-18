package com.hmdp.ai.domain.run;

public final class NodeRunClaim {
    private final String nodeRunId;
    private final boolean claimed;
    private final String outputJson;

    public NodeRunClaim(String nodeRunId, boolean claimed, String outputJson) {
        this.nodeRunId = nodeRunId;
        this.claimed = claimed;
        this.outputJson = outputJson;
    }

    public String getNodeRunId() { return nodeRunId; }
    public boolean isClaimed() { return claimed; }
    public String getOutputJson() { return outputJson; }
}
