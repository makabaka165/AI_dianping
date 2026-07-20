package com.hmdp.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.event.RunEventPublisher;
import com.hmdp.ai.domain.observability.InvocationContext;
import com.hmdp.ai.infrastructure.dify.HttpDifyWorkflowGateway;
import com.hmdp.ai.infrastructure.external.SafeHttpClient;
import com.hmdp.ai.infrastructure.model.SecretResolutionService;
import com.hmdp.ai.infrastructure.model.SecretResolver;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Tag("integration")
class DifyStreamingIntegrationTest {
    @Test
    void forwardsSseEventsAndKeepsOnlyFinalOutputs() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/workflow", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("data: {\"event\":\"workflow_started\","
                    + "\"workflow_run_id\":\"external-1\",\"data\":{}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            exchange.getResponseBody().write(("data: {\"event\":\"workflow_finished\","
                    + "\"workflow_run_id\":\"external-1\",\"data\":{\"outputs\":{\"answer\":\"ok\"}}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            SecretResolver resolver = new SecretResolver() {
                public boolean supports(String reference) { return true; }
                public String resolve(String reference) { return "test-secret"; }
            };
            RunEventPublisher events = mock(RunEventPublisher.class);
            HttpDifyWorkflowGateway gateway = new HttpDifyWorkflowGateway(new SafeHttpClient(),
                    new SecretResolutionService(Collections.singletonList(resolver)), mapper, events);
            com.fasterxml.jackson.databind.node.ObjectNode configuration = mapper.createObjectNode()
                    .put("allowedHost", "127.0.0.1").put("scheme", "http")
                    .put("port", server.getAddress().getPort()).put("path", "/workflow")
                    .put("secretRef", "env:TEST").put("responseMode", "streaming")
                    .put("allowPrivateNetwork", true);
            InvocationContext context = new InvocationContext("tenant", "workspace", "run", "node-run",
                    "invocation", "trace", "agent", 1, "user");

            com.fasterxml.jackson.databind.JsonNode result = gateway.run(configuration,
                    mapper.createObjectNode(), context, 5000);

            assertThat(result.path("outputs").path("answer").asText()).isEqualTo("ok");
            assertThat(result.path("externalInvocationId").asText()).isEqualTo("external-1");
            assertThat(result.has("events")).isFalse();
            verify(events, atLeast(2)).publish(anyString(), anyString(), anyString(),
                    anyString(), any(), anyBoolean());
        } finally {
            server.stop(0);
        }
    }
}
