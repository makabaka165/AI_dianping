package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.CacheProperties;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheClientTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Mock
    private ThreadPoolTaskExecutor cacheRebuildExecutor;

    private CacheProperties cacheProperties;
    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        cacheProperties = new CacheProperties();
        cacheClient = new CacheClient(stringRedisTemplate, redissonClient, cacheRebuildExecutor, cacheProperties);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void setShouldApplyTtlJitter() {
        cacheClient.set("cache:shop:1", shop(1L), 30L, TimeUnit.MINUTES);

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(eq("cache:shop:1"), anyString(), ttlCaptor.capture(), eq(TimeUnit.SECONDS));

        long minTtl = TimeUnit.MINUTES.toSeconds(30);
        long maxTtl = TimeUnit.MINUTES.toSeconds(35);
        assertThat(ttlCaptor.getValue()).isBetween(minTtl, maxTtl);
    }

    @Test
    void queryWithMutexShouldReturnCachedValueWithoutLock() {
        when(valueOperations.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(shop(1L)));
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = cacheClient.queryWithMutex(
                "cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(id);
                },
                30L, TimeUnit.MINUTES
        );

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(dbCalls).hasValue(0);
        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    void queryWithMutexShouldRebuildCacheWhenLockAcquired() throws InterruptedException {
        when(valueOperations.get("cache:shop:1")).thenReturn(null);
        when(redissonClient.getLock("lock:shop:1")).thenReturn(lock);
        when(lock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = cacheClient.queryWithMutex(
                "cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(id);
                },
                30L, TimeUnit.MINUTES
        );

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(dbCalls).hasValue(1);
        verify(valueOperations).set(eq("cache:shop:1"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        verify(lock).unlock();
    }

    @Test
    void queryWithMutexShouldReturnCacheWhenRetryHitsAfterLockTimeout() throws InterruptedException {
        cacheProperties.getMutex().getRetryAfterFail().setSleepMillis(1L);
        when(valueOperations.get("cache:shop:1"))
                .thenReturn(null, null, JSONUtil.toJsonStr(shop(1L)));
        when(redissonClient.getLock("lock:shop:1")).thenReturn(lock);
        when(lock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = cacheClient.queryWithMutex(
                "cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(id);
                },
                30L, TimeUnit.MINUTES
        );

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(dbCalls).hasValue(0);
        verify(valueOperations, never()).set(eq("cache:shop:1"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        verify(lock, never()).unlock();
    }

    @Test
    void queryWithMutexShouldThrowBusyAndSkipDbWhenLockTimeoutRetryMisses() throws InterruptedException {
        cacheProperties.getMutex().getRetryAfterFail().setMaxAttempts(1);
        cacheProperties.getMutex().getRetryAfterFail().setSleepMillis(1L);
        cacheProperties.getMutex().getRetryAfterFail().setFallbackToDb(false);
        when(valueOperations.get("cache:shop:1")).thenReturn(null);
        when(redissonClient.getLock("lock:shop:1")).thenReturn(lock);
        when(lock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);
        AtomicInteger dbCalls = new AtomicInteger();

        assertThatThrownBy(() -> cacheClient.queryWithMutex(
                "cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(id);
                },
                30L, TimeUnit.MINUTES
        )).isInstanceOf(CacheBusyException.class);

        assertThat(dbCalls).hasValue(0);
        verify(valueOperations, never()).set(eq("cache:shop:1"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        verify(lock, never()).unlock();
    }

    @Test
    void queryWithMutexShouldFallbackToDbWhenConfiguredAfterLockTimeout() throws InterruptedException {
        cacheProperties.getMutex().getRetryAfterFail().setMaxAttempts(1);
        cacheProperties.getMutex().getRetryAfterFail().setSleepMillis(1L);
        cacheProperties.getMutex().getRetryAfterFail().setFallbackToDb(true);
        when(valueOperations.get("cache:shop:1")).thenReturn(null);
        when(redissonClient.getLock("lock:shop:1")).thenReturn(lock);
        when(lock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = cacheClient.queryWithMutex(
                "cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(id);
                },
                30L, TimeUnit.MINUTES
        );

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(dbCalls).hasValue(1);
        verify(valueOperations, never()).set(eq("cache:shop:1"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        verify(lock, never()).unlock();
    }

    @Test
    void queryWithMutexShouldPreserveInterruptAndSkipDbWhenInterrupted() throws InterruptedException {
        when(valueOperations.get("cache:shop:1")).thenReturn(null);
        when(redissonClient.getLock("lock:shop:1")).thenReturn(lock);
        when(lock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new InterruptedException("stop"));
        AtomicInteger dbCalls = new AtomicInteger();

        try {
            assertThatThrownBy(() -> cacheClient.queryWithMutex(
                    "cache:shop:", 1L, Shop.class,
                    id -> {
                        dbCalls.incrementAndGet();
                        return shop(id);
                    },
                    30L, TimeUnit.MINUTES
            )).isInstanceOf(CacheBusyException.class);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(dbCalls).hasValue(0);
            verify(valueOperations, never()).set(eq("cache:shop:1"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
            verify(lock, never()).unlock();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void setWithLogicalExpireShouldSetPhysicalTtl() {
        cacheClient.setWithLogicalExpire("cache:logical:1", shop(1L), 20L, TimeUnit.SECONDS);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(eq("cache:logical:1"), jsonCaptor.capture(), ttlCaptor.capture(), eq(TimeUnit.SECONDS));

        RedisData redisData = JSONUtil.toBean(jsonCaptor.getValue(), RedisData.class);
        assertThat(redisData.getExpireTime()).isNotNull();
        assertThat(ttlCaptor.getValue()).isEqualTo(TimeUnit.MINUTES.toSeconds(10));
    }

    @Test
    void deleteWithMutexShouldWaitForRebuildAndDeleteCachedValue() {
        when(redissonClient.getLock("lock:shop:1")).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        cacheClient.deleteWithMutex("cache:shop:", 1L);

        verify(lock).lock();
        verify(stringRedisTemplate).delete("cache:shop:1");
        verify(lock).unlock();
    }

    private Shop shop(Long id) {
        Shop shop = new Shop();
        shop.setId(id);
        shop.setName("test-shop-" + id);
        return shop;
    }
}
