package com.hmdp.ai.infrastructure.dify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.application.agent.event.RunEventPublisher;
import com.hmdp.ai.domain.external.DifyWorkflowGateway;
import com.hmdp.ai.domain.observability.InvocationContext;
import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.ai.infrastructure.external.OutboundHttpRequest;
import com.hmdp.ai.infrastructure.external.OutboundHttpResponse;
import com.hmdp.ai.infrastructure.external.SafeHttpClient;
import com.hmdp.ai.infrastructure.model.SecretNotConfiguredException;
import com.hmdp.ai.infrastructure.model.SecretResolutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class HttpDifyWorkflowGateway implements DifyWorkflowGateway {
    private final SafeHttpClient http;
    private final SecretResolutionService secrets;
    private final ObjectMapper mapper;
    private final RunEventPublisher events;

    public HttpDifyWorkflowGateway(SafeHttpClient http, SecretResolutionService secrets, ObjectMapper mapper) {
        this(http, secrets, mapper, null);
    }

    @Autowired
    public HttpDifyWorkflowGateway(SafeHttpClient http, SecretResolutionService secrets, ObjectMapper mapper,
                                   RunEventPublisher events) {
        this.http = http;
        this.secrets = secrets;
        this.mapper = mapper;
        this.events = events;
    }

    @Override
    public JsonNode run(JsonNode config, JsonNode input, InvocationContext context, int timeoutMs) {
        try {
            DifyAppConfiguration configuration = new DifyAppConfiguration(config);
            ObjectNode body = mapper.createObjectNode();
            body.set("inputs", input);
            body.put("response_mode", configuration.getResponseMode());
            body.put("user", context.getUserId());
            body.put("run_id", context.getRunId());
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + secrets.resolve(configuration.getSecretRef()));
            headers.put("Content-Type", "application/json");
            headers.put("Accept", configuration.getResponseMode().equals("streaming")
                    ? "text/event-stream" : "application/json");
            Set<String> types = new LinkedHashSet<>(Arrays.asList("application/json", "text/event-stream"));
            OutboundHttpRequest request = new OutboundHttpRequest(new URI(configuration.getScheme(), null,
                    configuration.getHost(), configuration.getPort(), configuration.getPath(), null, null),
                    "POST", headers, mapper.writeValueAsBytes(body), Duration.ofMillis(timeoutMs),
                    configuration.getMaxResponseBytes(), types, configuration.isAllowPrivate());
            if (configuration.getResponseMode().equals("streaming")) {
                return stream(request, context);
            }
            OutboundHttpResponse response = http.execute(request, context.getRunId());
            ensureSuccess(response);
            return mapper.readTree(response.getBody());
        } catch (SecretNotConfiguredException e) {
            throw new IllegalArgumentException("DIFY_PROVIDER_NOT_CONFIGURED", e);
        } catch (IllegalArgumentException | java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("DIFY_EXECUTION_FAILED", e);
        }
    }

    private JsonNode stream(OutboundHttpRequest request, InvocationContext context) {
        AtomicReference<JsonNode> outputs = new AtomicReference<>(NullNode.getInstance());
        AtomicReference<String> externalId = new AtomicReference<>();
        OutboundHttpResponse response = http.streamLines(request, context.getRunId(), line -> {
            if (!line.startsWith("data:")) return;
            try {
                JsonNode event = mapper.readTree(line.substring(5).trim());
                String id = event.path("workflow_run_id").asText(
                        event.path("task_id").asText(externalId.get()));
                externalId.set(id);
                if ("workflow_finished".equals(event.path("event").asText())) {
                    outputs.set(event.path("data").path("outputs"));
                }
                publish(context, event, id);
            } catch (Exception e) {
                throw new IllegalArgumentException("DIFY_STREAM_EVENT_INVALID", e);
            }
        });
        ensureSuccess(response);
        if (!"text/event-stream".equals(response.getContentType())) {
            throw new IllegalArgumentException("DIFY_STREAM_CONTENT_TYPE_INVALID");
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("outputs", outputs.get());
        if (externalId.get() != null) result.put("externalInvocationId", externalId.get());
        return result;
    }

    private void publish(InvocationContext context, JsonNode event, String externalId) {
        if (events == null) return;
        ObjectNode payload = mapper.createObjectNode();
        payload.put("nodeRunId", context.getNodeRunId());
        payload.put("invocationId", context.getInvocationId());
        if (externalId != null) payload.put("externalInvocationId", externalId);
        payload.put("event", event.path("event").asText("message"));
        payload.put("dataSummary", AiLogSanitizer.safe(event.path("data").toString(), 4000));
        events.publish(context.getTenantId(), context.getWorkspaceId(), context.getRunId(),
                "dify.event", payload, false);
    }

    private void ensureSuccess(OutboundHttpResponse response) {
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            throw new IllegalStateException("DIFY_STATUS_" + response.getStatusCode());
        }
    }
}
