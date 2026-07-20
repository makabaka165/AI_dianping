package com.hmdp.ai.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.mcp.McpServer;
import org.springframework.stereotype.Component;

@Component
public class McpToolExecutor {
    private final McpClientFactory clients;

    public McpToolExecutor(McpClientFactory clients) { this.clients = clients; }

    public JsonNode execute(McpServer server, String tool, JsonNode input, String runId) {
        return clients.create(server).execute(server, tool, input, runId);
    }
}
