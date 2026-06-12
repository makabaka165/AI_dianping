package com.hmdp.ai.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class IntentSlotMemoryService {

    private static final String PREFIX = "hmdp:ai:intent:slots:";
    private static final long TTL_MINUTES = 30L;

    @Resource
    private RedissonClient redissonClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentSlotState load(String userId, String sessionId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(userId, sessionId));
            String json = bucket.get();
            if (json == null || json.trim().isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, IntentSlotState.class);
        } catch (Exception e) {
            log.debug("Load intent slot state failed, userId={}, sessionId={}", userId, sessionId, e);
            return null;
        }
    }

    public void save(String userId, String sessionId, IntentRouteCandidate candidate) {
        if (userId == null || sessionId == null || candidate == null || candidate.getIntent() == null) {
            return;
        }
        if (candidate.getIntent() == ShopAIIntent.FREE_CHAT || candidate.getIntent() == ShopAIIntent.UNSUPPORTED) {
            return;
        }
        try {
            IntentSlotState state = IntentSlotState.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .intent(candidate.getIntent())
                    .shopId(candidate.getShopId())
                    .shopId1(candidate.getShopId1())
                    .shopId2(candidate.getShopId2())
                    .aspect(candidate.getAspect())
                    .userPreference(candidate.getUserPreference())
                    .category(candidate.getCategory())
                    .limit(candidate.getLimit())
                    .updatedAtEpochMillis(System.currentTimeMillis())
                    .build();
            redissonClient.getBucket(key(userId, sessionId))
                    .set(objectMapper.writeValueAsString(state), TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("Save intent slot state failed, userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    private String key(String userId, String sessionId) {
        return PREFIX + safe(userId) + ":" + safe(sessionId);
    }

    private String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9_.:-]", "_");
    }
}
