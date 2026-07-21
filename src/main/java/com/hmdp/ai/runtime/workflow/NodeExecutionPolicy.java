package com.hmdp.ai.runtime.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class NodeExecutionPolicy {
    private static final long MAX_BACKOFF_MS = 30_000;

    private final long timeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final double backoffMultiplier;
    private final long maximumBackoffMs;
    private final Set<String> retryableErrors;

    private NodeExecutionPolicy(long timeoutMs, int maxAttempts, long initialBackoffMs,
                                double backoffMultiplier, long maximumBackoffMs,
                                Set<String> retryableErrors) {
        this.timeoutMs = timeoutMs;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.backoffMultiplier = backoffMultiplier;
        this.maximumBackoffMs = maximumBackoffMs;
        this.retryableErrors = Collections.unmodifiableSet(new LinkedHashSet<>(retryableErrors));
    }

    public static NodeExecutionPolicy from(WorkflowNodeDefinition node, ObjectMapper mapper) {
        try {
            JsonNode configuration = mapper.readTree(node.getConfigurationJson() == null
                    ? "{}" : node.getConfigurationJson());
            JsonNode retry = configuration.path("retryPolicy");
            if (retry.isTextual()) {
                retry = mapper.readTree(retry.asText());
            }
            if (!retry.isObject()) {
                retry = configuration;
            }
            Set<String> retryableErrors = new LinkedHashSet<>();
            JsonNode errors = retry.path("retryableErrors");
            if (errors.isArray()) {
                errors.forEach(value -> {
                    String code = value.asText("").trim();
                    if (!code.isEmpty()) {
                        retryableErrors.add(code);
                    }
                });
            }
            long backoff = Math.min(MAX_BACKOFF_MS, Math.max(0, retry.path("backoffMs").asLong(0)));
            double multiplier = Math.max(1.0, retry.path("backoffMultiplier").asDouble(2.0));
            long maximum = Math.max(backoff, Math.min(MAX_BACKOFF_MS,
                    Math.max(0, retry.path("maxBackoffMs").asLong(MAX_BACKOFF_MS))));
            return new NodeExecutionPolicy(Math.max(1, node.getTimeoutMs()),
                    Math.max(1, node.getMaxAttempts()), backoff, multiplier, maximum, retryableErrors);
        } catch (Exception e) {
            throw new IllegalStateException("NODE_RETRY_POLICY_INVALID", e);
        }
    }

    public long effectiveTimeoutMs(ExecutionContext context) {
        long remaining = Math.max(0, java.time.Duration.between(java.time.Instant.now(),
                context.getDeadline()).toMillis());
        return Math.min(timeoutMs, remaining);
    }

    public NodeRetryDecision decide(String errorCode, boolean executorMarkedRetryable, int attempt) {
        if (!executorMarkedRetryable || attempt >= maxAttempts) {
            return NodeRetryDecision.stop();
        }
        if (!retryableErrors.isEmpty() && !retryableErrors.contains(errorCode)) {
            return NodeRetryDecision.stop();
        }
        double scaled = initialBackoffMs * Math.pow(backoffMultiplier, Math.max(0, attempt - 1));
        return NodeRetryDecision.retryAfter(Math.min(maximumBackoffMs, (long) scaled));
    }

    public long getTimeoutMs() { return timeoutMs; }
    public int getMaxAttempts() { return maxAttempts; }
    public Set<String> getRetryableErrors() { return retryableErrors; }
}
