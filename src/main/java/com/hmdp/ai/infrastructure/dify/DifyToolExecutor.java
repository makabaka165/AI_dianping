package com.hmdp.ai.infrastructure.dify;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.external.DifyWorkflowGateway;
import com.hmdp.ai.domain.observability.InvocationContext;
import org.springframework.stereotype.Component;

@Component
public class DifyToolExecutor {
    private final DifyWorkflowGateway gateway;

    public DifyToolExecutor(DifyWorkflowGateway gateway) {
        this.gateway = gateway;
    }

    public JsonNode execute(JsonNode configuration, JsonNode input, InvocationContext context, int timeoutMs) {
        return gateway.run(configuration, input, context, timeoutMs);
    }
}
