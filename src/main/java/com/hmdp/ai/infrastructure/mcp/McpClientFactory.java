package com.hmdp.ai.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.mcp.McpServer;
import com.hmdp.ai.infrastructure.external.SafeHttpClient;
import com.hmdp.ai.infrastructure.model.SecretResolutionService;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpClientFactory {
    private final SafeHttpClient http;
    private final SecretResolutionService secrets;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, McpHttpClient> sessions = new ConcurrentHashMap<>();

    public McpClientFactory(SafeHttpClient http, SecretResolutionService secrets, ObjectMapper mapper) {
        this.http = http;
        this.secrets = secrets;
        this.mapper = mapper;
    }

    public McpHttpClient create() {
        return new McpHttpClient(http, secrets, mapper);
    }

    public McpHttpClient create(McpServer server) {
        String key = server.getTenantId() + '\u001f' + server.getWorkspaceId() + '\u001f'
                + server.getId() + '\u001f' + server.getEndpoint();
        return sessions.computeIfAbsent(key, ignored -> create());
    }
}
