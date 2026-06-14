package com.hmdp.ai.memory;

import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.EvidenceType;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.dto.ai.ShopSummaryResult;
import com.hmdp.ai.memory.RedissonChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private RedissonChatMemoryStore chatMemoryStore;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RKeys keys;

    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new MemoryService();
        ReflectionTestUtils.setField(memoryService, "chatMemoryStore", chatMemoryStore);
        ReflectionTestUtils.setField(memoryService, "redissonClient", redissonClient);
    }

    @Test
    void writeSummaryMemoryShouldOverwriteSnapshotInsteadOfAppending() {
        ShopSummaryResult result = ShopSummaryResult.builder()
                .shopId(1L)
                .coreSummary("服务稳定，环境不错")
                .keyPoints(List.of("服务", "环境"))
                .build();
        ShopAnalysisContext context = ShopAnalysisContext.builder()
                .evidence(List.of(EvidenceItem.builder()
                        .id("review:10")
                        .type(EvidenceType.REVIEW)
                        .sourceId(10L)
                        .snippet("服务很热情，环境也干净")
                        .build()))
                .build();

        memoryService.writeSummaryMemory("summary-memory", result, context);

        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMemoryStore, never()).getMessages(any());
        verify(chatMemoryStore).updateMessages(eq("summary-memory"), messagesCaptor.capture());
        assertThat(messagesCaptor.getValue()).hasSize(2);
        assertThat(messagesCaptor.getValue().get(0).text()).contains("店铺ID=1");
        assertThat(messagesCaptor.getValue().get(1).text())
                .contains("服务稳定")
                .contains("#review:10");
    }

    @Test
    void clearAllUserMemoryShouldUseIndexFirst() {
        List<String> indexed = List.of("hmdp:memory:shop:qa:1:u1", "hmdp:memory:ai:chat:u1:s1");
        when(chatMemoryStore.getIndexedMemoryIdsByUser("u1")).thenReturn(indexed);
        when(chatMemoryStore.deleteMemoryKeys(indexed)).thenReturn(2);

        Map<String, Integer> result = memoryService.clearAllUserMemory("u1");

        assertThat(result.get("total")).isEqualTo(2);
        assertThat(result.get("index:user:u1")).isEqualTo(2);
        verify(redissonClient, never()).getKeys();
    }

    @Test
    void clearAllShopSummaryMemoryShouldUseShopIndexFirst() {
        List<String> indexed = List.of("hmdp:memory:shop:summary:7:u1", "hmdp:memory:shop:summary:7:u2");
        when(chatMemoryStore.getIndexedShopSummaryMemoryIds(7L)).thenReturn(indexed);
        when(chatMemoryStore.deleteMemoryKeys(indexed)).thenReturn(2);

        int deleted = memoryService.clearAllShopSummaryMemory(7L);

        assertThat(deleted).isEqualTo(2);
        verify(redissonClient, never()).getKeys();
    }

    @Test
    void indexMissingShouldFallbackToBoundedScanForUserMemory() {
        when(chatMemoryStore.getIndexedMemoryIdsByUser("u1")).thenReturn(List.of());
        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeysStreamByPattern(anyString(), eq(100)))
                .thenReturn(Stream.of("legacy-summary"))
                .thenReturn(Stream.of("legacy-qa"))
                .thenReturn(Stream.empty())
                .thenReturn(Stream.empty())
                .thenReturn(Stream.empty());
        when(chatMemoryStore.deleteMemoryKeys(List.of("legacy-summary"))).thenReturn(1);
        when(chatMemoryStore.deleteMemoryKeys(List.of("legacy-qa"))).thenReturn(1);
        when(chatMemoryStore.deleteMemoryKeys(List.of())).thenReturn(0);

        Map<String, Integer> result = memoryService.clearAllUserMemory("u1");

        assertThat(result.get("total")).isEqualTo(2);
        verify(keys, org.mockito.Mockito.times(5)).getKeysStreamByPattern(anyString(), eq(100));
    }

    @Test
    void indexMissingShouldFallbackToBoundedScanForShopSummaryMemory() {
        when(chatMemoryStore.getIndexedShopSummaryMemoryIds(7L)).thenReturn(List.of());
        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeysStreamByPattern("hmdp:memory:shop:summary:7:*", 100))
                .thenReturn(Stream.of("legacy-summary"));
        when(chatMemoryStore.deleteMemoryKeys(List.of("legacy-summary"))).thenReturn(1);

        int deleted = memoryService.clearAllShopSummaryMemory(7L);

        assertThat(deleted).isEqualTo(1);
        verify(keys).getKeysStreamByPattern("hmdp:memory:shop:summary:7:*", 100);
    }
}
