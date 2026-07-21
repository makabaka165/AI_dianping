package com.hmdp.ai.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolInvocation;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;

@Component
public class ToolReliabilityExecutor {
    private final ObjectMapper mapper;
    private final CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();
    private final RetryRegistry retries = RetryRegistry.ofDefaults();
    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();

    public ToolReliabilityExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public <T> Callable<T> decorate(ToolDefinition definition, ToolInvocation invocation,
                                    Callable<T> operation) {
        JsonNode retryPolicy = read(definition.getRetryPolicyJson());
        JsonNode configuration = read(definition.getConfigurationJson());
        String key = key(definition, invocation);

        CircuitBreakerConfig circuitConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(configuration.path("failureRateThreshold").floatValue() > 0
                        ? configuration.path("failureRateThreshold").floatValue() : 50.0f)
                .minimumNumberOfCalls(Math.max(2, configuration.path("minimumCircuitCalls").asInt(4)))
                .slidingWindowSize(Math.max(2, configuration.path("circuitWindowSize").asInt(10)))
                .permittedNumberOfCallsInHalfOpenState(Math.max(1,
                        configuration.path("halfOpenPermittedCalls").asInt(2)))
                .waitDurationInOpenState(Duration.ofMillis(Math.max(100,
                        configuration.path("openStateWaitMs").asLong(10_000))))
                .build();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(Math.max(1, configuration.path("maxConcurrentCalls").asInt(10)))
                .maxWaitDuration(Duration.ofMillis(Math.max(0,
                        configuration.path("bulkheadWaitMs").asLong(0))))
                .build();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(Math.max(1, retryPolicy.path("maxAttempts").asInt(1)))
                .waitDuration(Duration.ofMillis(Math.max(0, retryPolicy.path("waitDurationMs").asLong(100))))
                .retryExceptions(Exception.class)
                .build();

        CircuitBreaker breaker = circuitBreakers.circuitBreaker(key, circuitConfig);
        Bulkhead bulkhead = bulkheads.bulkhead(key, bulkheadConfig);
        Retry retry = retries.retry(key, retryConfig);
        Callable<T> decorated = Bulkhead.decorateCallable(bulkhead, operation);
        decorated = CircuitBreaker.decorateCallable(breaker, decorated);
        return Retry.decorateCallable(retry, decorated);
    }

    CircuitBreaker circuitBreaker(String name) {
        return circuitBreakers.circuitBreaker(name);
    }

    String circuitBreakerState(ToolDefinition definition, ToolInvocation invocation) {
        return circuitBreakers.circuitBreaker(key(definition, invocation)).getState().name();
    }

    private String key(ToolDefinition definition, ToolInvocation invocation) {
        return invocation.getContext().getTenantId() + ':' + definition.getProtocol().name() + ':'
                + definition.getCode() + ":execute";
    }

    private JsonNode read(String value) {
        try {
            return mapper.readTree(value == null || value.trim().isEmpty() ? "{}" : value);
        } catch (Exception e) {
            throw new IllegalStateException("tool reliability policy is invalid", e);
        }
    }
}
