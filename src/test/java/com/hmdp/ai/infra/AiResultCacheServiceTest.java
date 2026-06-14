package com.hmdp.ai.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiResultCacheServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RKeys keys;

    private AiResultCacheService service;

    @BeforeEach
    void setUp() {
        service = new AiResultCacheService();
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        when(redissonClient.getKeys()).thenReturn(keys);
    }

    @Test
    void evictShopShouldUseScanStreamAndUnlink() {
        String pattern = "hmdp:ai:result:shop:7:*";
        when(keys.getKeysStreamByPattern(pattern, 100)).thenReturn(Stream.of("k1", "k2"));
        when(keys.unlink("k1")).thenReturn(1L);
        when(keys.unlink("k2")).thenReturn(1L);

        service.evictShop(7L);

        verify(keys).getKeysStreamByPattern(pattern, 100);
        verify(keys).unlink("k1");
        verify(keys).unlink("k2");
        verify(keys, never()).getKeysByPattern(pattern);
    }
}
