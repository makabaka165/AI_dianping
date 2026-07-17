package com.hmdp.ai.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ai.IntentRouteCandidate;
import com.hmdp.dto.ai.IntentSlotState;
import com.hmdp.dto.ai.ShopAIIntent;
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
    private static final long PENDING_TTL_MINUTES = 10L;

    @Resource(name = "memoryRedissonClient")
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

    public void savePending(String userId, String sessionId, IntentRouteCandidate candidate) {
        if (userId == null || sessionId == null || candidate == null || candidate.getIntent() == null) {
            return;
        }
        if (candidate.getIntent() == ShopAIIntent.FREE_CHAT || candidate.getIntent() == ShopAIIntent.UNSUPPORTED) {
            return;
        }
        try {
            IntentSlotState existing = load(userId, sessionId);
            IntentSlotState state = existing == null ? IntentSlotState.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .build() : existing;
            state.setPendingIntent(candidate.getIntent());
            state.setPendingShopId(candidate.getShopId());
            state.setPendingShopId1(candidate.getShopId1());
            state.setPendingShopId2(candidate.getShopId2());
            state.setPendingAspect(candidate.getAspect());
            state.setPendingUserPreference(candidate.getUserPreference());
            state.setPendingCategory(candidate.getCategory());
            state.setPendingLimit(candidate.getLimit());
            state.setMissingFields(candidate.safeMissingParams());
            state.setPendingUpdatedAtEpochMillis(System.currentTimeMillis());
            redissonClient.getBucket(key(userId, sessionId))
                    .set(objectMapper.writeValueAsString(state), TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("Save pending intent slot state failed, userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    public void clearPending(String userId, String sessionId) {
        try {
            IntentSlotState state = load(userId, sessionId);
            if (state == null) {
                return;
            }
            clearPendingFields(state);
            redissonClient.getBucket(key(userId, sessionId))
                    .set(objectMapper.writeValueAsString(state), TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("Clear pending intent slot state failed, userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    public boolean pendingExpired(IntentSlotState state) {
        if (state == null || state.getPendingUpdatedAtEpochMillis() == null) {
            return true;
        }
        long ageMillis = System.currentTimeMillis() - state.getPendingUpdatedAtEpochMillis();
        return ageMillis > TimeUnit.MINUTES.toMillis(PENDING_TTL_MINUTES);
    }

    private void clearPendingFields(IntentSlotState state) {
        state.setPendingIntent(null);
        state.setPendingShopId(null);
        state.setPendingShopId1(null);
        state.setPendingShopId2(null);
        state.setPendingAspect(null);
        state.setPendingUserPreference(null);
        state.setPendingCategory(null);
        state.setPendingLimit(null);
        state.setMissingFields(null);
        state.setPendingUpdatedAtEpochMillis(null);
    }

    private String key(String userId, String sessionId) {
        return PREFIX + safe(userId) + ":" + safe(sessionId);
    }

    private String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9_.:-]", "_");
    }
}
