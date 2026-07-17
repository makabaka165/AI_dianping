package com.hmdp.ai.memory;

import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private RBucket<String> bucket;

    @Mock
    private RSet<String> userIndex;

    @Mock
    private RSet<String> functionIndex;

    @Mock
    private RSet<String> shopSummaryIndex;

    private RedissonChatMemoryStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getKeys()).thenReturn(keys);
        store = new RedissonChatMemoryStore(redissonClient, keyManager);
        ReflectionTestUtils.setField(store, "ttlConfigCache", new java.util.concurrent.ConcurrentHashMap<>());
        ReflectionTestUtils.setField(store, "defaultTtlSeconds", 7200L);
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

    @Test
    void updateMessagesShouldIndexMemoryKey() {
        String memoryId = "hmdp:memory:shop:summary:7:u1";
        when(redissonClient.<String>getBucket(memoryId)).thenReturn(bucket);
        when(keyManager.getFunctionType(memoryId)).thenReturn(ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX);
        when(keyManager.getUserId(memoryId)).thenReturn("u1");
        when(keyManager.getShopSummaryShopId(memoryId)).thenReturn(7L);
        when(keyManager.buildFunctionIndexKey(ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX))
                .thenReturn("hmdp:memory:index:function:shop:summary");
        when(keyManager.buildUserIndexKey("u1")).thenReturn("hmdp:memory:index:user:u1");
        when(keyManager.buildShopSummaryIndexKey(7L)).thenReturn("hmdp:memory:index:shop-summary:7");
        when(redissonClient.<String>getSet("hmdp:memory:index:function:shop:summary")).thenReturn(functionIndex);
        when(redissonClient.<String>getSet("hmdp:memory:index:user:u1")).thenReturn(userIndex);
        when(redissonClient.<String>getSet("hmdp:memory:index:shop-summary:7")).thenReturn(shopSummaryIndex);

        store.updateMessages(memoryId, List.of(UserMessage.from("生成店铺总结")));

        verify(bucket).set(org.mockito.ArgumentMatchers.anyString(), eq(3600L), eq(TimeUnit.SECONDS));
        verify(functionIndex).add(memoryId);
        verify(userIndex).add(memoryId);
        verify(shopSummaryIndex).add(memoryId);
        verify(functionIndex).expire(anyLong(), eq(TimeUnit.SECONDS));
        verify(userIndex).expire(anyLong(), eq(TimeUnit.SECONDS));
        verify(shopSummaryIndex).expire(anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void updateMessagesShouldUseConfiguredFunctionTtl() {
        String memoryId = "hmdp:memory:shop:summary:7:u1";
        ReflectionTestUtils.setField(store, "shopSummaryTtlSeconds", 1234L);
        store.init();
        when(redissonClient.<String>getBucket(memoryId)).thenReturn(bucket);
        when(keyManager.getFunctionType(memoryId)).thenReturn(ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX);
        when(keyManager.getUserId(memoryId)).thenReturn(null);
        when(keyManager.getShopSummaryShopId(memoryId)).thenReturn(null);
        when(keyManager.buildFunctionIndexKey(ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX))
                .thenReturn("hmdp:memory:index:function:shop:summary");
        when(redissonClient.<String>getSet("hmdp:memory:index:function:shop:summary")).thenReturn(functionIndex);

        store.updateMessages(memoryId, List.of(UserMessage.from("summary")));

        verify(bucket).set(org.mockito.ArgumentMatchers.anyString(), eq(3600L), eq(TimeUnit.SECONDS));
    }

    @Test
    void deleteMessagesShouldRemoveIndexes() {
        String memoryId = "hmdp:memory:shop:summary:7:u1";
        when(redissonClient.<String>getBucket(memoryId)).thenReturn(bucket);
        when(bucket.delete()).thenReturn(true);
        when(keyManager.getFunctionType(memoryId)).thenReturn(ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX);
        when(keyManager.getUserId(memoryId)).thenReturn("u1");
        when(keyManager.getShopSummaryShopId(memoryId)).thenReturn(7L);
        when(keyManager.buildFunctionIndexKey(ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX))
                .thenReturn("hmdp:memory:index:function:shop:summary");
        when(keyManager.buildUserIndexKey("u1")).thenReturn("hmdp:memory:index:user:u1");
        when(keyManager.buildShopSummaryIndexKey(7L)).thenReturn("hmdp:memory:index:shop-summary:7");
        when(redissonClient.<String>getSet("hmdp:memory:index:function:shop:summary")).thenReturn(functionIndex);
        when(redissonClient.<String>getSet("hmdp:memory:index:user:u1")).thenReturn(userIndex);
        when(redissonClient.<String>getSet("hmdp:memory:index:shop-summary:7")).thenReturn(shopSummaryIndex);

        store.deleteMessages(memoryId);

        verify(functionIndex).remove(memoryId);
        verify(userIndex).remove(memoryId);
        verify(shopSummaryIndex).remove(memoryId);
    }

    @Test
    void getMessagesShouldNotDeleteMemoryOnTransientRedisReadFailure() {
        String memoryId = "hmdp:memory:ai:chat:u1:s1";
        when(redissonClient.<String>getBucket(memoryId)).thenReturn(bucket);
        when(bucket.get()).thenThrow(new IllegalStateException("redis down"));

        assertThat(store.getMessages(memoryId)).isEmpty();

        verify(bucket, never()).delete();
    }

    @Test
    void getMessagesShouldDeleteConfirmedCorruptJson() {
        String memoryId = "hmdp:memory:ai:chat:u1:s1";
        when(redissonClient.<String>getBucket(memoryId)).thenReturn(bucket);
        when(bucket.get()).thenReturn("{not-json");

        assertThat(store.getMessages(memoryId)).isEmpty();

        verify(bucket).delete();
    }

    @Test
    void getIndexedMemoryIdsShouldRemoveStaleMembers() {
        String indexKey = "hmdp:memory:index:user:u1";
        when(keyManager.buildUserIndexKey("u1")).thenReturn(indexKey);
        when(redissonClient.<String>getSet(indexKey)).thenReturn(userIndex);
        when(userIndex.isExists()).thenReturn(true);
        when(userIndex.readAll()).thenReturn(Set.of("active", "stale"));
        RBucket<String> activeBucket = org.mockito.Mockito.mock(RBucket.class);
        RBucket<String> staleBucket = org.mockito.Mockito.mock(RBucket.class);
        when(redissonClient.<String>getBucket("active")).thenReturn(activeBucket);
        when(redissonClient.<String>getBucket("stale")).thenReturn(staleBucket);
        when(activeBucket.isExists()).thenReturn(true);
        when(staleBucket.isExists()).thenReturn(false);

        List<String> result = store.getIndexedMemoryIdsByUser("u1");

        assertThat(result).containsExactly("active");
        verify(userIndex).remove("stale");
    }

    @Test
    void getMemoryStatsShouldUseIndexWhenPresent() {
        String functionType = ChatMemoryKeyManager.SHOP_QA_PREFIX;
        String indexKey = "hmdp:memory:index:function:shop:qa";
        String memoryId = "hmdp:memory:shop:qa:1:u1";
        when(keyManager.buildFunctionIndexKey(functionType)).thenReturn(indexKey);
        when(redissonClient.<String>getSet(indexKey)).thenReturn(functionIndex);
        when(functionIndex.isExists()).thenReturn(true);
        when(functionIndex.readAll()).thenReturn(Set.of(memoryId));
        when(redissonClient.<String>getBucket(memoryId)).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(true);
        when(bucket.get()).thenReturn("[{\"type\":\"USER\",\"text\":\"hello\"}]");

        java.util.Map<String, Integer> stats = store.getMemoryStatsByFunction(functionType);

        assertThat(stats.get("totalSessions")).isEqualTo(1);
        assertThat(stats.get("totalMessages")).isEqualTo(1);
        verify(keys, never()).getKeysStreamByPattern(anyString(), eq(100));
    }
}
