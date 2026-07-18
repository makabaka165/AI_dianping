package com.hmdp.ai.application.dto.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.run.RunEvent;

import java.time.Instant;

public final class AgentRunEventResponse {
    private final long sequence;
    private final String runId;
    private final String type;
    private final JsonNode payload;
    private final Instant createdAt;

    public AgentRunEventResponse(RunEvent event, JsonNode payload) {
        this.sequence = event.getSequence();
        this.runId = event.getRunId();
        this.type = event.getType();
        this.payload = payload;
        this.createdAt = event.getCreatedAt();
    }

    public long getSequence() { return sequence; }
    public String getRunId() { return runId; }
    public String getType() { return type; }
    public JsonNode getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
