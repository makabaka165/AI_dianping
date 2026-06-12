package com.hmdp.ai.memory;

import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.EvidenceType;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.repository.RedissonChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private RedissonChatMemoryStore chatMemoryStore;

    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new MemoryService();
        ReflectionTestUtils.setField(memoryService, "chatMemoryStore", chatMemoryStore);
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
}
