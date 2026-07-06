package com.hmdp.controller;

import com.hmdp.ai.application.ShopAIApplicationService;
import com.hmdp.ai.memory.ChatMemoryKeyManager;
import com.hmdp.dto.Result;
import com.hmdp.dto.ai.ShopSummaryResult;
import com.hmdp.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopSummaryControllerSemanticsTest {

    @Mock
    private ShopAIApplicationService shopAIApplicationService;

    @Mock
    private ChatMemoryKeyManager keyManager;

    @Mock
    private CurrentUserService currentUserService;

    private ShopSummaryController controller;

    @BeforeEach
    void setUp() {
        controller = new ShopSummaryController();
        ReflectionTestUtils.setField(controller, "shopAIApplicationService", shopAIApplicationService);
        ReflectionTestUtils.setField(controller, "keyManager", keyManager);
        ReflectionTestUtils.setField(controller, "currentUserService", currentUserService);
        when(currentUserService.requireCurrentUserId()).thenReturn(100L);
    }

    @Test
    void getQualitySummaryShouldNotWriteMemory() {
        when(shopAIApplicationService.qualitySummary("100", 1L, 5, 10, false,
                "/api/shop-summary/{shopId}/quality"))
                .thenReturn(ShopSummaryResult.builder().shopId(1L).build());

        controller.getQualitySummary(1L, 5, 10);

        verify(shopAIApplicationService).qualitySummary("100", 1L, 5, 10, false,
                "/api/shop-summary/{shopId}/quality");
    }

    @Test
    void postQualitySummaryWithMemoryShouldWriteMemory() {
        when(shopAIApplicationService.qualitySummary("100", 1L, 5, 10, true,
                "/api/shop-summary/{shopId}/quality/with-memory"))
                .thenReturn(ShopSummaryResult.builder().shopId(1L).build());

        controller.getQualitySummaryWithMemory(1L, 5, 10);

        verify(shopAIApplicationService).qualitySummary("100", 1L, 5, 10, true,
                "/api/shop-summary/{shopId}/quality/with-memory");
    }

    @Test
    @SuppressWarnings("unchecked")
    void memoryStatusShouldNotExposeRedisKey() {
        when(keyManager.buildShopQAKey(1L, "100")).thenReturn("hmdp:memory:shop:qa:1:100");
        when(shopAIApplicationService.getMemoryTtl("hmdp:memory:shop:qa:1:100")).thenReturn(120L);
        when(shopAIApplicationService.hasMemory("hmdp:memory:shop:qa:1:100")).thenReturn(true);
        when(shopAIApplicationService.getMemoryMessageCount("hmdp:memory:shop:qa:1:100")).thenReturn(2);

        Result result = controller.getMemoryStatus(1L, "qa");
        Map<String, Object> data = (Map<String, Object>) result.getData();

        assertThat(data).containsEntry("type", "qa");
        assertThat(data).containsEntry("exists", true);
        assertThat(data).containsEntry("messageCount", 2);
        assertThat(data).doesNotContainKeys("memoryKey", "memoryId");
    }
}
