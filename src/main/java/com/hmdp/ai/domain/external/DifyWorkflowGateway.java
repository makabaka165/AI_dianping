package com.hmdp.ai.domain.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.observability.InvocationContext;

public interface DifyWorkflowGateway {
    JsonNode run(JsonNode configuration, JsonNode input, InvocationContext context, int timeoutMs);
}
