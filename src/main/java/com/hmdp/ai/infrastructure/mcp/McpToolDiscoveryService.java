package com.hmdp.ai.infrastructure.mcp;

import com.hmdp.ai.domain.mcp.McpServer;
import com.hmdp.ai.domain.mcp.McpToolDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class McpToolDiscoveryService {
    private final McpClientFactory clients;

    public McpToolDiscoveryService(McpClientFactory clients) {
        this.clients = clients;
    }

    public List<McpToolDescriptor> discover(McpServer server) {
        McpHttpClient client = clients.create(server);
        client.initialize(server);
        client.initialized(server);
        return client.tools(server);
    }
}
