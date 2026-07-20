package com.hmdp.ai.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.mcp.McpServer;
import com.hmdp.ai.domain.mcp.McpToolDescriptor;
import com.hmdp.ai.domain.mcp.McpTransport;
import com.hmdp.ai.infrastructure.external.SafeHttpClient;
import com.hmdp.ai.infrastructure.model.SecretResolutionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpSessionLifecycleTest {
    @Test
    void usesOneInitializedSessionForPaginationAndToolCall() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> handle(exchange, mapper, methods));
        server.start();
        try {
            McpClientFactory factory = new McpClientFactory(new SafeHttpClient(),
                    new SecretResolutionService(Collections.emptyList()), mapper);
            McpServer definition = new McpServer("server", "tenant", "workspace", "local", "Local",
                    "local", McpTransport.HTTP,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", null, "[]",
                    2000, true, "UP", true, null, "ACTIVE");
            McpHttpClient client = factory.create(definition);

            List<McpToolDescriptor> tools = client.tools(definition);
            JsonNode output = client.execute(definition, "echo",
                    mapper.createObjectNode().put("value", "ok"));

            assertThat(tools).extracting(McpToolDescriptor::getName).containsExactly("first", "second");
            assertThat(output.path("content").path(0).path("text").asText()).isEqualTo("ok");
            assertThat(methods).containsExactly("initialize", "notifications/initialized",
                    "tools/list", "tools/list", "tools/call");
        } finally {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange, ObjectMapper mapper, List<String> methods) {
        try {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            methods.add(method);
            String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (!"initialize".equals(method) && !"session-1".equals(session)) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
            }
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            JsonNode result = result(method, request, mapper);
            byte[] body = mapper.writeValueAsBytes(mapper.createObjectNode().put("jsonrpc", "2.0")
                    .set("result", result));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            exchange.close();
        }
    }

    private JsonNode result(String method, JsonNode request, ObjectMapper mapper) {
        if ("initialize".equals(method)) {
            return mapper.createObjectNode().put("protocolVersion", "2025-03-26");
        }
        if ("tools/list".equals(method)) {
            boolean secondPage = request.path("params").has("cursor");
            com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
            result.putArray("tools").add(mapper.createObjectNode()
                    .put("name", secondPage ? "second" : "first")
                    .set("inputSchema", mapper.createObjectNode().put("type", "object")));
            if (!secondPage) result.put("nextCursor", "page-2");
            return result;
        }
        com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
        result.putArray("content")
                .add(mapper.createObjectNode().put("type", "text").put("text", "ok"));
        return result;
    }
}
