package com.hmdp.ai.infrastructure.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.tool.ToolBudgetPort;
import com.hmdp.ai.domain.tool.ToolIdempotencyPort;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class RedisToolExecutionStateAdapter implements ToolIdempotencyPort, ToolBudgetPort {
    private final RedissonClient redis;
    private final ObjectMapper mapper;

    public RedisToolExecutionStateAdapter(@Qualifier("businessRedissonClient") RedissonClient redis,
                                          ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public Optional<JsonNode> find(String tenantId, String workspaceId, String key) {
        RBucket<String> bucket = redis.getBucket(idempotencyKey(tenantId, workspaceId, key));
        String value = bucket.get();
        if (value == null) return Optional.empty();
        try {
            return Optional.of(mapper.readTree(value));
        } catch (Exception e) {
            bucket.delete();
            throw new IllegalStateException("cached tool result is invalid", e);
        }
    }

    @Override
    public void store(String tenantId, String workspaceId, String key, JsonNode result, Duration ttl) {
        try {
            redis.<String>getBucket(idempotencyKey(tenantId, workspaceId, key))
                    .set(mapper.writeValueAsString(result), ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("tool result could not be cached", e);
        }
    }

    @Override
    public boolean reserve(String tenantId, String workspaceId, String runId, int maximumCalls, Duration ttl) {
        RAtomicLong counter = redis.getAtomicLong("hmdp:ai:tool:budget:" + tenantId + ':' + workspaceId + ':' + runId);
        long value = counter.incrementAndGet();
        if (value == 1) counter.expire(ttl.toMillis(), TimeUnit.MILLISECONDS);
        if (value <= maximumCalls) return true;
        counter.decrementAndGet();
        return false;
    }

    private String idempotencyKey(String tenantId, String workspaceId, String key) {
        return "hmdp:ai:tool:idempotency:" + tenantId + ':' + workspaceId + ':' + key;
    }
}
