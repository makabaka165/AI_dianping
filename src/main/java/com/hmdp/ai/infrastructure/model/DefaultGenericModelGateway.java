package com.hmdp.ai.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.model.ModelProfileVersionRepository;
import com.hmdp.ai.domain.model.ModelType;
import com.hmdp.ai.runtime.model.GenericModelGateway;
import com.hmdp.ai.runtime.model.ModelCallRecorder;
import com.hmdp.ai.runtime.model.ModelInvocation;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.runtime.cancellation.CancellationToken;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Component
public class DefaultGenericModelGateway implements GenericModelGateway {
    private final ModelProfileVersionRepository profiles;
    private final ModelClientCache clients;
    private final ModelCallRecorder recorder;
    private final ObjectMapper mapper;
    private final RunCancellationRegistry cancellations;
    private final Map<String, Semaphore> bulkheads = new ConcurrentHashMap<>();
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();

    public DefaultGenericModelGateway(ModelProfileVersionRepository profiles, ModelClientCache clients,
                                      ModelCallRecorder recorder, ObjectMapper mapper) {
        this(profiles, clients, recorder, mapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultGenericModelGateway(ModelProfileVersionRepository profiles, ModelClientCache clients,
                                      ModelCallRecorder recorder, ObjectMapper mapper,
                                      RunCancellationRegistry cancellations) {
        this.profiles = profiles;
        this.clients = clients;
        this.recorder = recorder;
        this.mapper = mapper;
        this.cancellations = cancellations;
    }

    @Override
    public ModelInvocationResult invoke(ModelInvocation invocation) {
        throwIfCancelled(invocation);
        ModelProfileVersion profile = profiles.findById(
                        invocation.getContext().getInvocationContext().getTenantId(),
                        invocation.getContext().getInvocationContext().getWorkspaceId(),
                        invocation.getModelProfileVersionId())
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND,
                        "model profile version not found"));
        try {
            return invokeProfile(profile, invocation);
        } catch (RuntimeException primary) {
            if (profile.getFallbackModelProfileVersionId() == null) throw primary;
            ModelProfileVersion fallback = profiles.findById(profile.getTenantId(), profile.getWorkspaceId(),
                            profile.getFallbackModelProfileVersionId())
                    .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND,
                            "fallback model profile version not found"));
            ModelInvocation fallbackInvocation = new ModelInvocation(invocation.getContext(), fallback.getId(),
                    invocation.getSystemPrompt(), invocation.getUserPrompt(), invocation.getResponseFormat(),
                    invocation.getOutputSchema(), invocation.getTemperature(), invocation.getMaxOutputTokens(),
                    invocation.isStreaming(), invocation.getRequestSummary());
            return invokeProfile(fallback, fallbackInvocation);
        }
    }

    private ModelInvocationResult invokeProfile(ModelProfileVersion profile, ModelInvocation invocation) {
        validate(profile, invocation);
        JsonNode retryPolicy = json(profile.getRetryPolicyJson());
        int maxAttempts = Math.max(1, Math.min(5, retryPolicy.path("maxAttempts").asInt(1)));
        long backoffMillis = Math.max(0, Math.min(10_000, retryPolicy.path("backoffMillis").asLong(200)));
        int permits = Math.max(1, Math.min(64, retryPolicy.path("bulkheadPermits").asInt(8)));
        String key = profile.getId();
        Semaphore bulkhead = bulkheads.computeIfAbsent(key, ignored -> new Semaphore(permits));
        CircuitState circuit = circuits.computeIfAbsent(key, ignored -> new CircuitState());
        circuit.checkOpen();
        boolean acquired = bulkhead.tryAcquire();
        if (!acquired) throw new IllegalStateException("MODEL_BULKHEAD_FULL");
        long started = System.currentTimeMillis();
        try {
            RuntimeException last = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    throwIfCancelled(invocation);
                    ModelInvocationResult result = clients.get(profile).invoke(invocation);
                    circuit.succeeded();
                    recorder.success(profile, invocation, result, started);
                    return result;
                } catch (java.util.concurrent.CancellationException error) {
                    recorder.failure(profile, invocation, "MODEL_INVOCATION_CANCELLED", started,
                            System.currentTimeMillis() - started);
                    throw error;
                } catch (RuntimeException error) {
                    last = error;
                    if (attempt < maxAttempts) sleep(backoffMillis * attempt);
                }
            }
            circuit.failed(retryPolicy.path("circuitFailureThreshold").asInt(5),
                    retryPolicy.path("circuitOpenMillis").asLong(30_000));
            recorder.failure(profile, invocation, code(last), started, System.currentTimeMillis() - started);
            throw last == null ? new IllegalStateException("MODEL_INVOCATION_FAILED") : last;
        } finally {
            bulkhead.release();
        }
    }

    private void validate(ModelProfileVersion profile, ModelInvocation invocation) {
        if (!"PUBLISHED".equals(profile.getStatus()) && !"ARCHIVED".equals(profile.getStatus())) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT,
                    "model profile version is not immutable and executable");
        }
        if (profile.getModelType() != ModelType.CHAT) mismatch("chat");
        JsonNode capabilities = json(profile.getCapabilitiesJson());
        if (invocation.isStreaming() && !capabilities.path("streaming").asBoolean(false)) mismatch("streaming");
        if ("JSON".equalsIgnoreCase(invocation.getResponseFormat())
                && !capabilities.path("jsonSchema").asBoolean(false)) mismatch("jsonSchema");
        int output = invocation.getMaxOutputTokens() == null ? profile.getMaxOutputTokens()
                : invocation.getMaxOutputTokens();
        long estimatedInput = Math.max(1,
                (invocation.getSystemPrompt().length() + invocation.getUserPrompt().length()) / 4L);
        if (output <= 0 || output > profile.getMaxOutputTokens()
                || estimatedInput + output > profile.getContextWindow()) {
            throw new AiPlatformException(ErrorCode.AI_MODEL_CAPABILITY_MISMATCH,
                    "MODEL_CAPABILITY_MISMATCH: token budget");
        }
    }

    private void mismatch(String capability) {
        throw new AiPlatformException(ErrorCode.AI_MODEL_CAPABILITY_MISMATCH,
                "MODEL_CAPABILITY_MISMATCH: " + capability);
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("stored model profile JSON is invalid", e);
        }
    }

    private void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
        }
    }

    private void throwIfCancelled(ModelInvocation invocation) {
        if (cancellations == null) return;
        String runId = invocation.getContext().getInvocationContext().getRunId();
        CancellationToken token = cancellations.token(runId);
        if (token != null) token.throwIfCancelled();
    }

    private String code(Throwable error) {
        if (error instanceof SecretNotConfiguredException) return "AI_PROVIDER_NOT_CONFIGURED";
        String message = error == null ? null : error.getMessage();
        if (message == null || !message.matches("[A-Z0-9_]{3,64}")) return "MODEL_INVOCATION_FAILED";
        return message;
    }

    private static final class CircuitState {
        private int failures;
        private Instant openUntil;

        synchronized void checkOpen() {
            if (openUntil != null && openUntil.isAfter(Instant.now())) {
                throw new IllegalStateException("MODEL_CIRCUIT_OPEN");
            }
            if (openUntil != null) {
                openUntil = null;
                failures = 0;
            }
        }

        synchronized void succeeded() {
            failures = 0;
            openUntil = null;
        }

        synchronized void failed(int threshold, long openMillis) {
            failures++;
            if (failures >= Math.max(1, threshold)) {
                openUntil = Instant.now().plusMillis(Math.max(1000, openMillis));
            }
        }
    }
}
