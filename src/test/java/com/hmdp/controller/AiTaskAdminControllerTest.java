package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.ai.task.AiTaskService;
import com.hmdp.ai.task.AiTaskStreamService;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskEvent;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import com.hmdp.service.CurrentUserService;
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
class AiTaskAdminControllerTest {

    @Mock
    private AiTaskService aiTaskService;

    @Mock
    private AiTaskStreamService aiTaskStreamService;

    @Mock
    private CurrentUserService currentUserService;

    private AiTaskAdminController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new AiTaskAdminController();
        ReflectionTestUtils.setField(controller, "aiTaskService", aiTaskService);
        ReflectionTestUtils.setField(controller, "aiTaskStreamService", aiTaskStreamService);
        ReflectionTestUtils.setField(controller, "currentUserService", currentUserService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void submitBatchSummaryTaskShouldReturnTaskId() throws Exception {
        when(currentUserService.getCurrentUserId()).thenReturn(99L);
        when(aiTaskService.submit(eq(AiTaskType.BATCH_SHOP_SUMMARY), anyMap(), eq("99"))).thenReturn("task-batch");

        mockMvc.perform(post("/api/shop-summary/admin/ai/tasks/batch-summary")
                        .param("shopLimit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value("task-batch"));

        verify(aiTaskService).submit(eq(AiTaskType.BATCH_SHOP_SUMMARY), anyMap(), eq("99"));
    }

    @Test
    void getTaskShouldReturnTaskWhenExists() throws Exception {
        AiTask task = AiTask.builder()
                .taskId("task-1")
                .type(AiTaskType.BATCH_SHOP_SUMMARY)
                .status(AiTaskStatus.SUCCESS)
                .dedupKey("BATCH_SHOP_SUMMARY:{shopLimit=10}:99")
                .params(new LinkedHashMap<>())
                .build();
        when(aiTaskService.get("task-1")).thenReturn(Optional.of(task));

        mockMvc.perform(get("/api/shop-summary/admin/ai/tasks/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.dedupKey").doesNotExist());
    }

    @Test
    void getTaskShouldReturnFailWhenMissing() throws Exception {
        when(aiTaskService.get("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/shop-summary/admin/ai/tasks/missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("任务不存在"));
    }

    @Test
    void streamTaskShouldReturnTaskEventsWhenExists() {
        AiTask task = AiTask.builder()
                .taskId("task-1")
                .type(AiTaskType.BATCH_SHOP_SUMMARY)
                .status(AiTaskStatus.RUNNING)
                .build();
        AiTaskEvent event = AiTaskEvent.builder()
                .taskId("task-1")
                .status(AiTaskStatus.SUCCESS)
                .timestampEpochMillis(123L)
                .build();
        when(aiTaskService.get("task-1")).thenReturn(Optional.of(task));
        when(aiTaskStreamService.stream("task-1")).thenReturn(Flux.just(event));

        List<ServerSentEvent<AiTaskEvent>> events = controller.streamTask("task-1").collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("SUCCESS");
        assertThat(events.get(0).data()).isSameAs(event);
    }

    @Test
    void streamTaskShouldReturnErrorEventWhenMissing() {
        when(aiTaskService.get("missing")).thenReturn(Optional.empty());

        ServerSentEvent<AiTaskEvent> event = controller.streamTask("missing").blockFirst();

        assertThat(event).isNotNull();
        assertThat(event.event()).isEqualTo("error");
        assertThat(event.data().getTaskId()).isEqualTo("missing");
        assertThat(event.data().getStatus()).isEqualTo(AiTaskStatus.FAILED);
        assertThat(event.data().getErrorMessage()).isEqualTo("任务不存在");
    }

    @Test
    void endpointsShouldRequireRagManagePermission() throws Exception {
        Method submitBatchSummaryTask = AiTaskAdminController.class.getMethod("submitBatchSummaryTask", Integer.class);
        Method getTask = AiTaskAdminController.class.getMethod("getTask", String.class);
        Method streamTask = AiTaskAdminController.class.getMethod("streamTask", String.class);

        assertThat(submitBatchSummaryTask.getAnnotation(SaCheckPermission.class).value())
                .containsExactly("ai:rag:manage");
        assertThat(getTask.getAnnotation(SaCheckPermission.class).value())
                .containsExactly("ai:rag:manage");
        assertThat(streamTask.getAnnotation(SaCheckPermission.class).value())
                .containsExactly("ai:rag:manage");
    }
}
