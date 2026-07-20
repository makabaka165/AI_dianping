package com.hmdp.ai.domain.knowledge;

import java.time.Duration;
import java.util.List;

public interface IndexBuildRequestRepository {
    List<OutboxEvent> findAvailable(String consumerName, int limit);
    boolean claim(String eventId, String consumerName, String workerId, Duration lease);
    void complete(String eventId, String consumerName);
    boolean fail(String eventId, String consumerName, String payloadJson, String reason, int maxAttempts);
    boolean replay(String deadLetterId, String actorId);
}
