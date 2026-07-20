package com.hmdp.ai.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.runtime.model.ModelInvocation;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.runtime.cancellation.CancellationToken;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class OpenAiCompatibleModelAdapter implements ModelClient {
    private final ModelProfileVersion profile;
    private final SecretResolutionService secrets;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final RunCancellationRegistry cancellations;

    OpenAiCompatibleModelAdapter(ModelProfileVersion profile, SecretResolutionService secrets,
                                 ObjectMapper mapper, HttpClient client,
                                 RunCancellationRegistry cancellations) {
        this.profile = profile;
        this.secrets = secrets;
        this.mapper = mapper;
        this.client = client;
        this.cancellations = cancellations;
    }

    @Override
    public ModelInvocationResult invoke(ModelInvocation invocation) {
        long started = System.currentTimeMillis();
        String runId = invocation.getContext().getInvocationContext().getRunId();
        try {
            throwIfCancelled(runId);
            ObjectNode request = request(invocation);
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint())
                    .timeout(Duration.ofMillis(profile.getTimeoutMs()))
                    .header("Authorization", "Bearer " + secrets.resolve(profile.getSecretRef()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(request)))
                    .build();
            CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(
                    httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (cancellations != null) cancellations.track(runId, future);
            HttpResponse<byte[]> response;
            try {
                response = future.get(Math.max(1, profile.getTimeoutMs()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
            } finally {
                if (cancellations != null) cancellations.untrack(runId, future);
            }
            throwIfCancelled(runId);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("MODEL_PROVIDER_STATUS_" + response.statusCode());
            }
            JsonNode body = mapper.readTree(response.body());
            String content = body.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null) throw new IllegalStateException("MODEL_PROVIDER_RESPONSE_INVALID");
            JsonNode usage = body.path("usage");
            boolean estimated = !usage.isObject();
            long inputTokens = estimated ? estimate(invocation.getSystemPrompt() + invocation.getUserPrompt())
                    : usage.path("prompt_tokens").asLong(0);
            long outputTokens = estimated ? estimate(content) : usage.path("completion_tokens").asLong(0);
            JsonNode structured = null;
            if ("JSON".equalsIgnoreCase(invocation.getResponseFormat())) structured = mapper.readTree(content);
            return new ModelInvocationResult(content, structured, inputTokens, outputTokens, estimated,
                    System.currentTimeMillis() - started, cost(inputTokens, outputTokens),
                    body.path("id").asText(null));
        } catch (SecretNotConfiguredException e) {
            throw e;
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MODEL_PROVIDER_CALL_FAILED", e);
        }
    }

    private void throwIfCancelled(String runId) {
        CancellationToken token = cancellations == null ? null : cancellations.token(runId);
        if (token != null) token.throwIfCancelled();
    }

    private ObjectNode request(ModelInvocation invocation) throws Exception {
        ObjectNode defaults = (ObjectNode) mapper.readTree(profile.getDefaultParametersJson());
        ObjectNode request = defaults.deepCopy();
        request.put("model", profile.getModelName());
        request.put("stream", false);
        request.put("max_tokens", invocation.getMaxOutputTokens() == null
                ? profile.getMaxOutputTokens() : Math.min(profile.getMaxOutputTokens(), invocation.getMaxOutputTokens()));
        if (invocation.getTemperature() != null) request.put("temperature", invocation.getTemperature());
        ArrayNode messages = request.putArray("messages");
        if (!invocation.getSystemPrompt().isEmpty()) {
            messages.addObject().put("role", "system").put("content", invocation.getSystemPrompt());
        }
        messages.addObject().put("role", "user").put("content", invocation.getUserPrompt());
        if ("JSON".equalsIgnoreCase(invocation.getResponseFormat())) {
            request.putObject("response_format").put("type", "json_object");
        }
        return request;
    }

    private URI endpoint() {
        String base = profile.getBaseUrl().replaceAll("/+$", "");
        if (base.endsWith("/chat/completions")) return URI.create(base);
        return URI.create(base + "/chat/completions");
    }

    private long estimate(String value) {
        return Math.max(1, (value == null ? 0 : value.length()) / 4L);
    }

    private BigDecimal cost(long input, long output) {
        return profile.getInputTokenPrice().multiply(BigDecimal.valueOf(input))
                .add(profile.getOutputTokenPrice().multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000));
    }
}
