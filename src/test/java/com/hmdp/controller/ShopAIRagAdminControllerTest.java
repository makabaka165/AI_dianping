package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.ai.application.ShopAICacheInvalidationService;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import com.hmdp.ai.retrieval.ShopReviewVectorIndexService;
import com.hmdp.service.ShopStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ShopAIRagAdminControllerTest {

    @Mock
    private ShopReviewVectorIndexService vectorIndexService;

    @Mock
    private ShopAICacheInvalidationService cacheInvalidationService;

    @Mock
    private ShopStatsService shopStatsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ShopAIRagAdminController controller = new ShopAIRagAdminController();
        ReflectionTestUtils.setField(controller, "shopReviewVectorIndexService", vectorIndexService);
        ReflectionTestUtils.setField(controller, "shopAICacheInvalidationService", cacheInvalidationService);
        ReflectionTestUtils.setField(controller, "shopStatsService", shopStatsService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void rebuildShopShouldCallService() throws Exception {
        when(vectorIndexService.rebuildShop(7L, 20)).thenReturn(result(7L));

        mockMvc.perform(post("/api/shop-summary/admin/rag/shops/7/rebuild").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shopId").value(7))
                .andExpect(jsonPath("$.data.indexed").value(3));

        verify(vectorIndexService).rebuildShop(7L, 20);
    }

    @Test
    void rebuildAllShouldCallService() throws Exception {
        when(vectorIndexService.rebuildAll(10, 20)).thenReturn(result(null));

        mockMvc.perform(post("/api/shop-summary/admin/rag/rebuild")
                        .param("shopLimit", "10")
                        .param("perShopLimit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.indexed").value(3));

        verify(vectorIndexService).rebuildAll(10, 20);
    }

    @Test
    void compactShopShouldClearCacheAndCallService() throws Exception {
        ShopRagRebuildResult compacted = ShopRagRebuildResult.builder()
                .shopId(7L)
                .indexed(3)
                .skipped(1)
                .failed(0)
                .durationMs(12L)
                .message("RAG review compact completed as rebuild/refresh only. Current LangChain4j RedisEmbeddingStore does not support precise old vector deletion.")
                .build();
        when(vectorIndexService.compactShop(7L, 20)).thenReturn(compacted);

        mockMvc.perform(post("/api/shop-summary/admin/rag/shops/7/compact").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shopId").value(7))
                .andExpect(jsonPath("$.data.indexed").value(3))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("does not support precise old vector deletion")));

        verify(cacheInvalidationService).clearShopRelatedCaches(7L);
        verify(shopStatsService).evictShopStatsCache(7L);
        verify(vectorIndexService).compactShop(7L, 20);
    }

    @Test
    void compactShopShouldReturnOkWhenServiceUnavailable() throws Exception {
        when(vectorIndexService.compactShop(7L, 20)).thenThrow(new RuntimeException("embedding unavailable"));

        mockMvc.perform(post("/api/shop-summary/admin/rag/shops/7/compact").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shopId").value(7))
                .andExpect(jsonPath("$.data.indexed").value(0))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("unavailable")));

        verify(cacheInvalidationService).clearShopRelatedCaches(7L);
        verify(shopStatsService).evictShopStatsCache(7L);
    }

    @Test
    void endpointsShouldRequireRagManagePermission() throws Exception {
        Method rebuildShop = ShopAIRagAdminController.class.getMethod("rebuildShop", Long.class, Integer.class);
        Method compactShop = ShopAIRagAdminController.class.getMethod("compactShop", Long.class, Integer.class);
        Method rebuildAll = ShopAIRagAdminController.class.getMethod("rebuildAll", Integer.class, Integer.class);

        assertThat(rebuildShop.getAnnotation(SaCheckPermission.class).value()).containsExactly("ai:rag:manage");
        assertThat(compactShop.getAnnotation(SaCheckPermission.class).value()).containsExactly("ai:rag:manage");
        assertThat(rebuildAll.getAnnotation(SaCheckPermission.class).value()).containsExactly("ai:rag:manage");
    }

    private ShopRagRebuildResult result(Long shopId) {
        return ShopRagRebuildResult.builder()
                .shopId(shopId)
                .indexed(3)
                .skipped(1)
                .failed(0)
                .durationMs(12L)
                .message("ok")
                .build();
    }
}
