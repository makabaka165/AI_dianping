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

    @Mock
    private AiTaskEventPublisher eventPublisher;

    private AiTaskService service;

    @BeforeEach
    void setUp() {
        service = new AiTaskService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "queue", queue);
        ReflectionTestUtils.setField(service, "aiMetricsService", aiMetricsService);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(service, "runningTimeoutMinutes", 30L);
        ReflectionTestUtils.setField(service, "maxRetryCount", 3);
        ReflectionTestUtils.setField(service, "stuckScanLimit", 100);
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

    @Test
    void recoverStuckRunningTasksShouldIgnoreFreshHeartbeat() throws Exception {
        AiTask fresh = runningTask("fresh", now() - 60_000L, 0);
        when(repository.findByStatus(AiTaskStatus.RUNNING, 100)).thenReturn(java.util.List.of(fresh));

        int recovered = service.recoverStuckRunningTasks(now());

        assertThat(recovered).isZero();
        verify(queue, never()).enqueue(anyString());
        verify(repository, never()).clearInflight(anyString());
    }

    @Test
    void recoverStuckRunningTasksShouldRequeueTimedOutTaskUnderRetryLimit() throws Exception {
        long now = now();
        AiTask stuck = runningTask("stuck", now - 31 * 60_000L, 1);
        when(repository.findByStatus(AiTaskStatus.RUNNING, 100)).thenReturn(java.util.List.of(stuck));

        int recovered = service.recoverStuckRunningTasks(now);

        assertThat(recovered).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(AiTaskStatus.PENDING);
        assertThat(stuck.getRetryCount()).isEqualTo(2);
        assertThat(stuck.getErrorMessage()).isEqualTo("task heartbeat timeout, requeued");
        verify(repository).clearInflight("dedup-stuck");
        verify(repository).update(stuck);
        verify(queue).enqueue("stuck");
        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.any());
        verify(aiMetricsService).increment("ai.task.requeued", "ai_task", false);
    }

    @Test
    void recoverStuckRunningTasksShouldFailTimedOutTaskAfterRetryLimit() throws InterruptedException {
        long now = now();
        AiTask stuck = runningTask("stuck", now - 31 * 60_000L, 3);
        when(repository.findByStatus(AiTaskStatus.RUNNING, 100)).thenReturn(java.util.List.of(stuck));

        int recovered = service.recoverStuckRunningTasks(now);

        assertThat(recovered).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(AiTaskStatus.FAILED);
        assertThat(stuck.getFinishedAtEpochMillis()).isEqualTo(now);
        assertThat(stuck.getErrorMessage()).isEqualTo("task heartbeat timeout, max retry exceeded");
        verify(repository).clearInflight("dedup-stuck");
        verify(queue, never()).enqueue(anyString());
        verify(aiMetricsService).increment("ai.task.timeout.failed", "ai_task", true);
    }

    private Map<String, Object> params(Object... values) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            params.put((String) values[i], values[i + 1]);
        }
        return params;
    }

    private AiTask runningTask(String taskId, long heartbeatAt, int retryCount) {
        return AiTask.builder()
                .taskId(taskId)
                .type(AiTaskType.RAG_REBUILD_SHOP)
                .status(AiTaskStatus.RUNNING)
                .dedupKey("dedup-" + taskId)
                .retryCount(retryCount)
                .heartbeatAtEpochMillis(heartbeatAt)
                .updatedAtEpochMillis(heartbeatAt)
                .build();
    }

    private long now() {
        return 10_000_000L;
    }
}
