package com.hmdp.ai.task;

import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTaskServiceTest {

    @Mock
    private AiTaskRepository repository;

    @Mock
    private AiTaskQueue queue;

    @Mock
    private AiMetricsService aiMetricsService;

    private AiTaskService service;

    @BeforeEach
    void setUp() {
        service = new AiTaskService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "queue", queue);
        ReflectionTestUtils.setField(service, "aiMetricsService", aiMetricsService);
    }

    @Test
    void submitShouldPersistPendingTaskAndEnqueue() throws Exception {
        Map<String, Object> params = params("shopLimit", 10, "perShopLimit", 20);
        when(repository.tryRegisterInflight(anyString(), anyString())).thenReturn(Optional.empty());

        String taskId = service.submit(AiTaskType.RAG_REBUILD_ALL, params, "7");

        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(repository).save(taskCaptor.capture());
        verify(queue).enqueue(taskId);
        verify(aiMetricsService).increment("ai.task.submitted", "ai_task", false);

        AiTask task = taskCaptor.getValue();
        assertThat(task.getTaskId()).isEqualTo(taskId);
        assertThat(task.getType()).isEqualTo(AiTaskType.RAG_REBUILD_ALL);
        assertThat(task.getStatus()).isEqualTo(AiTaskStatus.PENDING);
        assertThat(task.getOwnerUserId()).isEqualTo("7");
        assertThat(task.getDedupKey()).contains("RAG_REBUILD_ALL");
        assertThat(task.getCreatedAtEpochMillis()).isPositive();
    }

    @Test
    void submitShouldReturnExistingTaskIdWhenDedupHit() throws Exception {
        Map<String, Object> params = params("shopId", 7L, "limit", 20);
        when(repository.tryRegisterInflight(anyString(), anyString())).thenReturn(Optional.of("existing-task"));

        String taskId = service.submit(AiTaskType.RAG_REBUILD_SHOP, params, "9");

        assertThat(taskId).isEqualTo("existing-task");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(AiTask.class));
        verify(queue, never()).enqueue(org.mockito.ArgumentMatchers.anyString());
        verify(aiMetricsService).increment("ai.task.dedup", "ai_task", false);
    }

    @Test
    void getShouldDelegateToRepository() {
        AiTask task = AiTask.builder().taskId("t1").build();
        when(repository.find("t1")).thenReturn(Optional.of(task));

        assertThat(service.get("t1")).contains(task);

        verify(repository).find(eq("t1"));
    }

    private Map<String, Object> params(Object... values) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            params.put((String) values[i], values[i + 1]);
        }
        return params;
    }
}
