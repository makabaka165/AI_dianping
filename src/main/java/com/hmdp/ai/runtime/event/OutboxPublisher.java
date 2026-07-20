package com.hmdp.ai.runtime.event;

import com.hmdp.ai.domain.knowledge.OutboxEvent;
import com.hmdp.ai.domain.knowledge.OutboxRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class OutboxPublisher {
    private final OutboxRepository outbox;
    private final RedisConnectionFactory redis;

    public OutboxPublisher(OutboxRepository outbox,
                           @Qualifier("businessRedisConnectionFactory") RedisConnectionFactory redis) {
        this.outbox = outbox;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${hmdp.ai.knowledge.outbox-fixed-delay-ms:1000}")
    public void publish() {
        for (OutboxEvent event : outbox.findPending(100)) {
            if (!outbox.claim(event.getId())) continue;
            try (RedisConnection connection = redis.getConnection()) {
                connection.execute("XADD", bytes("hmdp:ai:knowledge:events"), bytes("*"),
                        bytes("eventId"), bytes(event.getId()),
                        bytes("tenantId"), bytes(event.getTenantId()),
                        bytes("workspaceId"), bytes(event.getWorkspaceId()),
                        bytes("aggregateType"), bytes(event.getAggregateType()),
                        bytes("aggregateId"), bytes(event.getAggregateId()),
                        bytes("eventType"), bytes(event.getEventType()),
                        bytes("payload"), bytes(event.getPayloadJson()));
                outbox.published(event.getId());
            } catch (Exception e) {
                outbox.failed(event.getId(), e.getMessage());
            }
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
