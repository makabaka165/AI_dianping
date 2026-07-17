package com.hmdp.ai.quota;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AiUserQuotaService {

    private static final String MINUTE_PREFIX = "hmdp:ai:quota:min:";
    private static final String DAY_PREFIX = "hmdp:ai:quota:day:";
    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Resource(name = "memoryRedissonClient")
    private RedissonClient redissonClient;

    @Value("${hmdp.ai.quota.enabled:true}")
    private boolean enabled;

    @Value("${hmdp.ai.quota.minute-permits:10}")
    private long minutePermits;

    @Value("${hmdp.ai.quota.daily-permits:200}")
    private long dailyPermits;

    @Value("${hmdp.ai.quota.fail-open:false}")
    private boolean failOpen;

    public void checkAndConsume(String userId, String operation) {
        if (!enabled) {
            return;
        }
        String safeUserId = safe(userId);
        try {
            LocalDateTime now = LocalDateTime.now();
            long minuteCount = incrementWithTtl(
                    MINUTE_PREFIX + safeUserId + ":" + now.format(MINUTE_FORMATTER),
                    Duration.ofSeconds(90));
            if (minutePermits > 0 && minuteCount > minutePermits) {
                throw new AiQuotaExceededException("AI 请求过于频繁，请稍后再试");
            }

            long dayCount = incrementWithTtl(
                    DAY_PREFIX + safeUserId + ":" + now.format(DAY_FORMATTER),
                    Duration.ofHours(26));
            if (dailyPermits > 0 && dayCount > dailyPermits) {
                throw new AiQuotaExceededException("今日 AI 使用次数已达上限");
            }
        } catch (AiQuotaExceededException e) {
            throw e;
        } catch (Exception e) {
            if (failOpen) {
                log.warn("AI quota check failed open, userId={}, operation={}", safeUserId, operation, e);
                return;
            }
            throw AiQuotaExceededException.infra("AI 配额校验暂不可用", e);
        }
    }

    private long incrementWithTtl(String key, Duration ttl) {
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        long value = counter.incrementAndGet();
        if (value == 1L) {
            counter.expire(Math.max(1, ttl.getSeconds()), TimeUnit.SECONDS);
        }
        return value;
    }

    private String safe(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "anonymous";
        }
        return value.trim().replaceAll("[^a-zA-Z0-9_.:-]", "_");
    }
}
