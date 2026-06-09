package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.CacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ThreadPoolTaskExecutor cacheRebuildExecutor;
    private final CacheProperties cacheProperties;

    public CacheClient(StringRedisTemplate stringRedisTemplate,
                       RedissonClient redissonClient,
                       @Qualifier("cacheRebuildExecutor") ThreadPoolTaskExecutor cacheRebuildExecutor,
                       CacheProperties cacheProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
        this.cacheProperties = cacheProperties;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        long ttl = ttlWithJitter(time, unit);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), ttl, TimeUnit.SECONDS);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        long physicalTtlSeconds = logicalPhysicalTtlSeconds(time, unit);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), physicalTtlSeconds, TimeUnit.SECONDS);
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        R cached = readCache(key, type);
        if (cached != null) {
            return cached;
        }
        if (isNullValue(key)) {
            return null;
        }

        R r = dbFallback.apply(id);
        if (r == null) {
            cacheNullValue(key);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        log.warn("逻辑过期缓存命中，触发异步重建，key={}", key);
        submitCacheRebuild(key, id, dbFallback, time, unit);
        return r;
    }

    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        R cached = readCache(key, type);
        if (cached != null) {
            return cached;
        }
        if (isNullValue(key)) {
            return null;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(
                    cacheProperties.getMutex().getWaitTimeoutMillis(),
                    lockLeaseMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (!locked) {
                log.warn("获取缓存互斥锁超时，key={}", key);
                R cachedAfterWait = readCache(key, type);
                if (cachedAfterWait != null || isNullValue(key)) {
                    return cachedAfterWait;
                }
                return dbFallback.apply(id);
            }

            R cachedAfterLock = readCache(key, type);
            if (cachedAfterLock != null) {
                return cachedAfterLock;
            }
            if (isNullValue(key)) {
                return null;
            }

            R r = dbFallback.apply(id);
            if (r == null) {
                cacheNullValue(key);
                return null;
            }
            this.set(key, r, time, unit);
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取缓存互斥锁被中断，key={}", key, e);
            return dbFallback.apply(id);
        } finally {
            unlockQuietly(lock, locked);
        }
    }

    private <R, ID> void submitCacheRebuild(String key, ID id, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        try {
            cacheRebuildExecutor.execute(() -> {
                RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
                boolean locked = false;
                try {
                    locked = lock.tryLock(
                            cacheProperties.getMutex().getWaitTimeoutMillis(),
                            lockLeaseMillis(),
                            TimeUnit.MILLISECONDS
                    );
                    if (!locked) {
                        log.warn("缓存重建锁等待超限，key={}", key);
                        return;
                    }
                    R newR = dbFallback.apply(id);
                    if (newR == null) {
                        cacheNullValue(key);
                        return;
                    }
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("缓存异步重建获取锁被中断，key={}", key, e);
                } catch (Exception e) {
                    log.warn("缓存异步重建失败，key={}", key, e);
                } finally {
                    unlockQuietly(lock, locked);
                }
            });
        } catch (RuntimeException e) {
            log.warn("缓存异步重建任务提交失败，key={}", key, e);
        }
    }

    private <R> R readCache(String key, Class<R> type) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        return JSONUtil.toBean(json, type);
    }

    private boolean isNullValue(String key) {
        String json = stringRedisTemplate.opsForValue().get(key);
        return json != null && StrUtil.isBlank(json);
    }

    private void cacheNullValue(String key) {
        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
    }

    private long ttlWithJitter(Long time, TimeUnit unit) {
        long baseSeconds = Math.max(1L, unit.toSeconds(time));
        long jitterSeconds = TimeUnit.MINUTES.toSeconds(Math.max(0L, cacheProperties.getTtlJitterMinutes()));
        if (jitterSeconds <= 0) {
            return baseSeconds;
        }
        return baseSeconds + ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
    }

    private long logicalPhysicalTtlSeconds(Long time, TimeUnit unit) {
        long logicalSeconds = Math.max(1L, unit.toSeconds(time));
        long minSeconds = TimeUnit.MINUTES.toSeconds(cacheProperties.getLogical().getMinPhysicalTtlMinutes());
        return Math.max(logicalSeconds * 2, minSeconds);
    }

    private long lockLeaseMillis() {
        return TimeUnit.SECONDS.toMillis(cacheProperties.getMutex().getLeaseTimeSeconds());
    }

    private void unlockQuietly(RLock lock, boolean locked) {
        if (!locked) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("释放缓存锁失败", e);
        }
    }
}
