package com.hmdp.repository;

import com.hmdp.config.ChatMemoryKeyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedissonChatMemoryStoreTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private ChatMemoryKeyManager keyManager;

    @Mock
    private RKeys keys;

    private RedissonChatMemoryStore store;

    @BeforeEach
    void setUp() {
        when(redissonClient.getKeys()).thenReturn(keys);
        store = new RedissonChatMemoryStore(redissonClient, keyManager);
    }

    @Test
    void deleteMessagesByFunctionShouldUseScanStreamAndUnlink() {
        when(keyManager.buildPatternKey(ChatMemoryKeyManager.SHOP_QA_PREFIX))
                .thenReturn("hmdp:memory:shop:qa:*");
        when(keys.getKeysStreamByPattern("hmdp:memory:shop:qa:*", 100))
                .thenReturn(Stream.of("k1", "k2"));
        when(keys.unlink("k1")).thenReturn(1L);
        when(keys.unlink("k2")).thenReturn(1L);

        int count = store.deleteMessagesByFunction(ChatMemoryKeyManager.SHOP_QA_PREFIX);

        assertThat(count).isEqualTo(2);
        verify(keys).getKeysStreamByPattern("hmdp:memory:shop:qa:*", 100);
        verify(keys, never()).getKeysByPattern("hmdp:memory:shop:qa:*");
    }
}
