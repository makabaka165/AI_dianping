package com.hmdp.ai.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.domain.mcp.McpServer;
import com.hmdp.ai.domain.mcp.McpToolDescriptor;
import com.hmdp.ai.infrastructure.external.OutboundHttpRequest;
import com.hmdp.ai.infrastructure.external.OutboundHttpResponse;
import com.hmdp.ai.infrastructure.external.SafeHttpClient;
import com.hmdp.ai.infrastructure.model.SecretResolutionService;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class McpHttpClient {
    private final SafeHttpClient http;
    private final SecretResolutionService secrets;
    private final ObjectMapper mapper;
    private volatile boolean initialized;

    McpHttpClient(SafeHttpClient http, SecretResolutionService secrets, ObjectMapper mapper) {
        this.http = http;
        this.secrets = secrets;
        this.mapper = mapper;
    }

    public synchronized JsonNode initialize(McpServer server) {
        if (initialized) return mapper.createObjectNode().put("initialized", true);
        ObjectNode params = mapper.createObjectNode().put("protocolVersion", "2025-03-26");
        params.set("capabilities", mapper.createObjectNode());
        params.set("clientInfo", mapper.createObjectNode().put("name", "hmdp-agent-platform").put("version", "1"));
        JsonNode result = call(server, "initialize", params);
        initialized = true;
        return result;
    }

    public JsonNode initialized(McpServer server) {
        return call(server, "notifications/initialized", mapper.createObjectNode());
    }

    public List<McpToolDescriptor> tools(McpServer server) {
        ensureInitialized(server);
        JsonNode result = call(server, "tools/list", mapper.createObjectNode());
        List<McpToolDescriptor> tools = new ArrayList<>();
        for (JsonNode node : result.path("tools")) {
            tools.add(new McpToolDescriptor(node.path("name").asText(), node.path("description").asText(""),
                    node.has("inputSchema") ? node.get("inputSchema") : mapper.createObjectNode()));
        }
        return tools;
    }

    public JsonNode execute(McpServer server, String tool, JsonNode arguments) {
        ensureInitialized(server);
        ObjectNode params = mapper.createObjectNode().put("name", tool);
        params.set("arguments", arguments);
        return call(server, "tools/call", params);
    }

    private void ensureInitialized(McpServer server) {
        if (!initialized) {
            initialize(server);
            initialized(server);
        }
    }

    private JsonNode call(McpServer server, String method, JsonNode params) {
        try {
            ObjectNode body = mapper.createObjectNode().put("jsonrpc", "2.0")
                    .put("id", UUID.randomUUID().toString()).put("method", method);
            body.set("params", params);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json, text/event-stream");
            if (server.getSecretRef() != null && !server.getSecretRef().isEmpty()) {
                headers.put("Authorization", "Bearer " + secrets.resolve(server.getSecretRef()));
            }
            OutboundHttpResponse response = http.execute(new OutboundHttpRequest(URI.create(server.getEndpoint()),
                    "POST", headers, mapper.writeValueAsBytes(body), Duration.ofMillis(server.getTimeoutMs()),
                    2 * 1024 * 1024,
                    new LinkedHashSet<>(Arrays.asList("application/json", "text/event-stream")),
                    server.isAllowPrivateNetwork()));
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw new IllegalStateException("MCP_STATUS_" + response.getStatusCode());
            }
            if (response.getBody().length == 0 || response.getStatusCode() == 202 || response.getStatusCode() == 204) {
                return NullNode.getInstance();
            }
            JsonNode envelope = "text/event-stream".equals(response.getContentType())
                    ? sse(response.bodyAsUtf8()) : mapper.readTree(response.getBody());
            if (envelope.has("error")) throw new IllegalStateException("MCP_REMOTE_ERROR");
            return envelope.path("result");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MCP_CALL_FAILED", e);
        }
    }

    private JsonNode sse(String value) throws Exception {
        JsonNode last = NullNode.getInstance();
        for (String line : value.split("\\R")) {
            if (line.startsWith("data:")) last = mapper.readTree(line.substring(5).trim());
        }
        if (last.isNull()) throw new IllegalArgumentException("MCP_RESPONSE_INVALID");
        return last;
    }
}
