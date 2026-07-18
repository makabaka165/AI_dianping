package com.hmdp.ai.domain.run;

import java.time.Instant;

public final class RunEvent {
    private final long sequence;
    private final String runId;
    private final String type;
    private final String payloadJson;
    private final Instant createdAt;

    public RunEvent(long sequence, String runId, String type, String payloadJson, Instant createdAt) {
        this.sequence = sequence;
        this.runId = runId;
        this.type = type;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
    }

    public long getSequence() { return sequence; }
    public String getRunId() { return runId; }
    public String getType() { return type; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getCreatedAt() { return createdAt; }
}
