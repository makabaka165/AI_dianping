package com.hmdp.ai.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AiResultCacheService {

    private static final String PREFIX = "hmdp:ai:result:";
    private static final long TTL_SECONDS = 3600L;
    private static final int SCAN_BATCH_SIZE = 100;

    @Resource(name = "memoryRedissonClient")
    private RedissonClient redissonClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String buildShopAnalysisKey(Long shopId,
                                       String contextVersion,
                                       String promptVersion,
                                       String modelName,
                                       String analysisType,
                                       String paramsHash) {
        return PREFIX + "shop:" + shopId
                + ":ctx:" + safe(contextVersion)
                + ":prompt:" + promptVersion
                + ":model:" + safe(modelName)
                + ":type:" + analysisType
                + ":params:" + safe(paramsHash);
    }

    public <T> T get(String key, Class<T> type) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.debug("Read AI L2 cache failed, key={}", key, e);
            return null;
        }
    }

    public void put(String key, Object value) {
        try {
            redissonClient.getBucket(key).set(objectMapper.writeValueAsString(value), TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Write AI L2 cache failed, key={}", key, e);
        }
    }

    public void evictShop(Long shopId) {
        try {
            redissonClient.getKeys()
                    .getKeysStreamByPattern(PREFIX + "shop:" + shopId + ":*", SCAN_BATCH_SIZE)
                    .forEach(key -> redissonClient.getKeys().unlink(key));
        } catch (Exception e) {
            log.debug("Evict AI L2 cache failed, shopId={}", shopId, e);
        }
    }

    private String safe(String value) {
        return value == null ? "none" : value.replaceAll("[^a-zA-Z0-9_.:-]", "_");
    }
}
