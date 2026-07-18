package com.hmdp.ai.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;

public interface ToolIdempotencyPort {
    Optional<JsonNode> find(String tenantId, String workspaceId, String key);

    void store(String tenantId, String workspaceId, String key, JsonNode result, Duration ttl);
}
