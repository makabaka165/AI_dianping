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
    private volatile boolean ready;
    private volatile String sessionId;

    McpHttpClient(SafeHttpClient http, SecretResolutionService secrets, ObjectMapper mapper) {
        this.http = http;
        this.secrets = secrets;
        this.mapper = mapper;
    }

    public synchronized JsonNode initialize(McpServer server) {
        return initialize(server, null);
    }

    public synchronized JsonNode initialize(McpServer server, String runId) {
        if (initialized) return mapper.createObjectNode().put("initialized", true)
                .put("ready", ready);
        ObjectNode params = mapper.createObjectNode().put("protocolVersion", "2025-03-26");
        params.set("capabilities", mapper.createObjectNode());
        params.set("clientInfo", mapper.createObjectNode().put("name", "hmdp-agent-platform").put("version", "1"));
        JsonNode result = call(server, "initialize", params, runId, false);
        initialized = true;
        return result;
    }

    public JsonNode initialized(McpServer server) {
        return initialized(server, null);
    }

    public JsonNode initialized(McpServer server, String runId) {
        synchronized (this) {
            if (ready) return mapper.createObjectNode().put("ready", true);
            if (!initialized) initialize(server, runId);
            try {
                JsonNode result = call(server, "notifications/initialized", mapper.createObjectNode(), runId, true);
                ready = true;
                return result;
            } catch (RuntimeException error) {
                initialized = false;
                ready = false;
                sessionId = null;
                throw error;
            }
        }
    }

    public List<McpToolDescriptor> tools(McpServer server) {
        return tools(server, null);
    }

    public List<McpToolDescriptor> tools(McpServer server, String runId) {
        ensureInitialized(server, runId);
        List<McpToolDescriptor> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = call(server, "tools/list", params, runId, false);
            for (JsonNode node : result.path("tools")) {
                tools.add(new McpToolDescriptor(node.path("name").asText(),
                        node.path("description").asText(""),
                        node.has("inputSchema") ? node.get("inputSchema") : mapper.createObjectNode()));
            }
            cursor = result.path("nextCursor").asText(null);
        } while (cursor != null && !cursor.isEmpty());
        return tools;
    }

    public JsonNode execute(McpServer server, String tool, JsonNode arguments) {
        return execute(server, tool, arguments, null);
    }

    public JsonNode execute(McpServer server, String tool, JsonNode arguments, String runId) {
        ensureInitialized(server, runId);
        ObjectNode params = mapper.createObjectNode().put("name", tool);
        params.set("arguments", arguments);
        return call(server, "tools/call", params, runId, false);
    }

    private synchronized void ensureInitialized(McpServer server, String runId) {
        if (!ready) {
            initialize(server, runId);
            initialized(server, runId);
        }
    }

    private JsonNode call(McpServer server, String method, JsonNode params, String runId, boolean notification) {
        try {
            ObjectNode body = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method);
            if (!notification) body.put("id", UUID.randomUUID().toString());
            body.set("params", params);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json, text/event-stream");
            if (server.getSecretRef() != null && !server.getSecretRef().isEmpty()) {
                headers.put("Authorization", "Bearer " + secrets.resolve(server.getSecretRef()));
            }
            if (sessionId != null) headers.put("Mcp-Session-Id", sessionId);
            OutboundHttpResponse response = http.execute(new OutboundHttpRequest(URI.create(server.getEndpoint()),
                    "POST", headers, mapper.writeValueAsBytes(body), Duration.ofMillis(server.getTimeoutMs()),
                    2 * 1024 * 1024,
                    new LinkedHashSet<>(Arrays.asList("application/json", "text/event-stream")),
                    server.isAllowPrivateNetwork()), runId);
            String responseSession = response.firstHeader("Mcp-Session-Id");
            if (responseSession != null && !responseSession.trim().isEmpty()) sessionId = responseSession;
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw new IllegalStateException("MCP_STATUS_" + response.getStatusCode());
            }
            if (notification || response.getBody().length == 0
                    || response.getStatusCode() == 202 || response.getStatusCode() == 204) {
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
