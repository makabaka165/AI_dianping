package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.ai.application.ShopAICacheInvalidationService;
import com.hmdp.ai.task.AiTaskService;
import com.hmdp.ai.task.AiTaskStreamService;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskEvent;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import com.hmdp.ai.retrieval.ShopReviewVectorIndexService;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.ShopStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Mock
    private AiTaskService aiTaskService;

    @Mock
    private AiTaskStreamService aiTaskStreamService;

    @Mock
    private CurrentUserService currentUserService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ShopAIRagAdminController controller = new ShopAIRagAdminController();
        ReflectionTestUtils.setField(controller, "shopReviewVectorIndexService", vectorIndexService);
        ReflectionTestUtils.setField(controller, "shopAICacheInvalidationService", cacheInvalidationService);
        ReflectionTestUtils.setField(controller, "shopStatsService", shopStatsService);
        ReflectionTestUtils.setField(controller, "aiTaskService", aiTaskService);
        ReflectionTestUtils.setField(controller, "aiTaskStreamService", aiTaskStreamService);
        ReflectionTestUtils.setField(controller, "currentUserService", currentUserService);
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
    void submitRebuildAllTaskShouldReturnTaskId() throws Exception {
        when(currentUserService.getCurrentUserId()).thenReturn(99L);
        when(aiTaskService.submit(eq(AiTaskType.RAG_REBUILD_ALL), anyMap(), eq("99"))).thenReturn("task-all");

        mockMvc.perform(post("/api/shop-summary/admin/rag/tasks/rebuild")
                        .param("shopLimit", "10")
                        .param("perShopLimit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value("task-all"));

        verify(aiTaskService).submit(eq(AiTaskType.RAG_REBUILD_ALL), anyMap(), eq("99"));
    }

    @Test
    void submitRebuildShopTaskShouldReturnTaskId() throws Exception {
        when(currentUserService.getCurrentUserId()).thenReturn(99L);
        when(aiTaskService.submit(eq(AiTaskType.RAG_REBUILD_SHOP), anyMap(), eq("99"))).thenReturn("task-shop");

        mockMvc.perform(post("/api/shop-summary/admin/rag/tasks/shops/7/rebuild")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value("task-shop"));

        verify(aiTaskService).submit(eq(AiTaskType.RAG_REBUILD_SHOP), anyMap(), eq("99"));
    }

    @Test
    void getTaskShouldReturnTaskWhenExists() throws Exception {
        AiTask task = AiTask.builder()
                .taskId("task-1")
                .type(AiTaskType.RAG_REBUILD_ALL)
                .status(AiTaskStatus.SUCCESS)
                .dedupKey("RAG_REBUILD_ALL:{shopLimit=10}:99")
                .params(new LinkedHashMap<>())
                .build();
        when(aiTaskService.get("task-1")).thenReturn(Optional.of(task));

        mockMvc.perform(get("/api/shop-summary/admin/rag/tasks/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.dedupKey").doesNotExist());
    }

    @Test
    void getTaskShouldReturnFailWhenMissing() throws Exception {
        when(aiTaskService.get("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/shop-summary/admin/rag/tasks/missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("任务不存在"));
    }

    @Test
    void streamTaskShouldReturnTaskEventsWhenExists() {
        AiTask task = AiTask.builder()
                .taskId("task-1")
                .type(AiTaskType.RAG_REBUILD_ALL)
                .status(AiTaskStatus.RUNNING)
                .build();
        AiTaskEvent event = AiTaskEvent.builder()
                .taskId("task-1")
                .status(AiTaskStatus.SUCCESS)
                .progressCurrent(2)
                .progressTotal(2)
                .timestampEpochMillis(123L)
                .build();
        when(aiTaskService.get("task-1")).thenReturn(Optional.of(task));
        when(aiTaskStreamService.stream("task-1")).thenReturn(Flux.just(event));

        List<ServerSentEvent<AiTaskEvent>> events = new java.util.ArrayList<>();
        new ShopAIRagAdminController();
        ShopAIRagAdminController controller = new ShopAIRagAdminController();
        ReflectionTestUtils.setField(controller, "aiTaskService", aiTaskService);
        ReflectionTestUtils.setField(controller, "aiTaskStreamService", aiTaskStreamService);

        controller.streamTask("task-1").subscribe(events::add);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("SUCCESS");
        assertThat(events.get(0).data()).isSameAs(event);
    }

    @Test
    void streamTaskShouldReturnErrorEventWhenMissing() {
        when(aiTaskService.get("missing")).thenReturn(Optional.empty());
        ShopAIRagAdminController controller = new ShopAIRagAdminController();
        ReflectionTestUtils.setField(controller, "aiTaskService", aiTaskService);

        ServerSentEvent<AiTaskEvent> event = controller.streamTask("missing").blockFirst();

        assertThat(event).isNotNull();
        assertThat(event.event()).isEqualTo("error");
        assertThat(event.data().getTaskId()).isEqualTo("missing");
        assertThat(event.data().getStatus()).isEqualTo(AiTaskStatus.FAILED);
        assertThat(event.data().getErrorMessage()).isEqualTo("任务不存在");
    }

    @Test
    void endpointsShouldRequireRagManagePermission() throws Exception {
        Method rebuildShop = ShopAIRagAdminController.class.getMethod("rebuildShop", Long.class, Integer.class);
        Method compactShop = ShopAIRagAdminController.class.getMethod("compactShop", Long.class, Integer.class);
        Method rebuildAll = ShopAIRagAdminController.class.getMethod("rebuildAll", Integer.class, Integer.class);
        Method submitRebuildAllTask = ShopAIRagAdminController.class.getMethod("submitRebuildAllTask", Integer.class, Integer.class);
        Method submitRebuildShopTask = ShopAIRagAdminController.class.getMethod("submitRebuildShopTask", Long.class, Integer.class);
        Method getTask = ShopAIRagAdminController.class.getMethod("getTask", String.class);
        Method streamTask = ShopAIRagAdminController.class.getMethod("streamTask", String.class);

        assertThat(rebuildShop.getAnnotation(SaCheckPermission.class).value()).containsExactly("ai:rag:manage");
        assertThat(compactShop.getAnnotation(SaCheckPermission.class).value()).containsExactly("ai:rag:manage");
        assertThat(rebuildAll.getAnnotation(SaCheckPermission.class).value()).containsExactly("ai:rag:manage");
        assertThat(submitRebuildAllTask.getAnnotation(SaCheckPermission.class).value()).containsExactly("ai:rag:manage");
        assertThat(submitRebuildShopTask.getAnnotation(SaCheckPermission.class).value()).containsExactly("ai:rag:manage");
        assertThat(getTask.getAnnotation(SaCheckPermission.class).value()).containsExactly("ai:rag:manage");
        assertThat(streamTask.getAnnotation(SaCheckPermission.class).value()).containsExactly("ai:rag:manage");
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
