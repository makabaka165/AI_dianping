package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import com.hmdp.service.ShopReviewVectorIndexService;
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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ShopAIRagAdminController controller = new ShopAIRagAdminController();
        ReflectionTestUtils.setField(controller, "shopReviewVectorIndexService", vectorIndexService);
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
    void endpointsShouldRequireRagManagePermission() throws Exception {
        Method rebuildShop = ShopAIRagAdminController.class.getMethod("rebuildShop", Long.class, Integer.class);
        Method rebuildAll = ShopAIRagAdminController.class.getMethod("rebuildAll", Integer.class, Integer.class);

        assertThat(rebuildShop.getAnnotation(SaCheckPermission.class).value()).containsExactly("ai:rag:manage");
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
