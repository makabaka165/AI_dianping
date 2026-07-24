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
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private RLock taskLock;

    private AiTaskService service;

    @BeforeEach
    void setUp() {
        service = new AiTaskService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "queue", queue);
        ReflectionTestUtils.setField(service, "aiMetricsService", aiMetricsService);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(service, "runningTimeoutMinutes", 30L);
        ReflectionTestUtils.setField(service, "pendingTimeoutMinutes", 30L);
        ReflectionTestUtils.setField(service, "maxRetryCount", 3);
        ReflectionTestUtils.setField(service, "stuckScanLimit", 100);
        lenient().when(repository.executionLock(anyString())).thenReturn(taskLock);
        lenient().when(taskLock.tryLock()).thenReturn(true);
        lenient().when(taskLock.isHeldByCurrentThread()).thenReturn(true);
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
    void submitShouldRemainSuccessfulWhenMetricsRecordingFailsAfterEnqueue() throws Exception {
        when(repository.tryRegisterInflight(anyString(), anyString())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("metrics down"))
                .when(aiMetricsService).increment("ai.task.submitted", "ai_task", false);

        String taskId = service.submit(AiTaskType.RAG_REBUILD_ALL, Map.of("shopLimit", 10), "7");

        assertThat(taskId).isNotBlank();
        verify(queue).enqueue(taskId);
        verify(repository, never()).update(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).clearInflight(anyString(), anyString());
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
    void submitShouldMarkSavedTaskFailedWhenEnqueueFails() throws Exception {
        when(repository.tryRegisterInflight(anyString(), anyString())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("redis queue down")).when(queue).enqueue(anyString());

        assertThatThrownBy(() -> service.submit(AiTaskType.RAG_REBUILD_ALL, Map.of("shopLimit", 10), "7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis queue down");

        verify(repository).update(org.mockito.ArgumentMatchers.argThat(task ->
                task.getStatus() == AiTaskStatus.FAILED
                        && "AI task enqueue failed".equals(task.getErrorMessage())
                        && task.getFinishedAtEpochMillis() != null));
        verify(repository).clearInflight(anyString(), anyString());
        verify(aiMetricsService).increment("ai.task.submit.failed", "ai_task", true);
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
        verify(repository, never()).clearInflight(anyString(), anyString());
    }

    @Test
    void recoverStuckPendingTasksShouldIgnoreFreshTask() throws Exception {
        AiTask fresh = pendingTask("fresh", now() - 60_000L);
        when(repository.findByStatus(AiTaskStatus.PENDING, 100)).thenReturn(java.util.List.of(fresh));

        assertThat(service.recoverStuckPendingTasks(now())).isZero();

        verify(queue, never()).enqueue(anyString());
        verify(repository, never()).find("fresh");
    }

    @Test
    void recoverStuckPendingTasksShouldRequeueTimedOutTaskAndKeepDedupRegistration() throws Exception {
        long now = now();
        AiTask stuck = pendingTask("stuck-pending", now - 31 * 60_000L);
        when(repository.findByStatus(AiTaskStatus.PENDING, 100)).thenReturn(java.util.List.of(stuck));
        when(repository.find("stuck-pending")).thenReturn(Optional.of(stuck));

        int recovered = service.recoverStuckPendingTasks(now);

        assertThat(recovered).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(AiTaskStatus.PENDING);
        assertThat(stuck.getErrorMessage()).isEqualTo("task pending timeout, requeued");
        verify(repository).update(stuck);
        verify(queue).enqueue("stuck-pending");
        verify(repository, never()).clearInflight("dedup-stuck-pending", "stuck-pending");
        verify(aiMetricsService).increment("ai.task.pending.requeued", "ai_task", false);
    }

    @Test
    void recoverStuckPendingTasksShouldFailTaskWhenQueueRemainsUnavailable() throws Exception {
        long now = now();
        AiTask stuck = pendingTask("stuck-pending", now - 31 * 60_000L);
        when(repository.findByStatus(AiTaskStatus.PENDING, 100)).thenReturn(java.util.List.of(stuck));
        when(repository.find("stuck-pending")).thenReturn(Optional.of(stuck));
        doThrow(new IllegalStateException("redis queue down")).when(queue).enqueue("stuck-pending");

        int recovered = service.recoverStuckPendingTasks(now);

        assertThat(recovered).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(AiTaskStatus.FAILED);
        assertThat(stuck.getErrorMessage()).isEqualTo("task pending timeout, requeue failed");
        verify(repository, org.mockito.Mockito.times(2)).update(stuck);
        verify(repository).clearInflight("dedup-stuck-pending", "stuck-pending");
        verify(aiMetricsService).increment("ai.task.requeue.failed", "ai_task", true);
    }

    @Test
    void recoverStuckRunningTasksShouldRequeueTimedOutTaskUnderRetryLimit() throws Exception {
        long now = now();
        AiTask stuck = runningTask("stuck", now - 31 * 60_000L, 1);
        when(repository.findByStatus(AiTaskStatus.RUNNING, 100)).thenReturn(java.util.List.of(stuck));
        when(repository.find("stuck")).thenReturn(Optional.of(stuck));

        int recovered = service.recoverStuckRunningTasks(now);

        assertThat(recovered).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(AiTaskStatus.PENDING);
        assertThat(stuck.getRetryCount()).isEqualTo(2);
        assertThat(stuck.getErrorMessage()).isEqualTo("task heartbeat timeout, requeued");
        verify(repository, never()).clearInflight("dedup-stuck", "stuck");
        verify(repository).update(stuck);
        verify(queue).enqueue("stuck");
        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.any());
        verify(aiMetricsService).increment("ai.task.requeued", "ai_task", false);
    }

    @Test
    void recoverStuckRunningTasksShouldFailTaskWhenRequeueIsUnavailable() throws Exception {
        long now = now();
        AiTask stuck = runningTask("stuck", now - 31 * 60_000L, 1);
        when(repository.findByStatus(AiTaskStatus.RUNNING, 100)).thenReturn(java.util.List.of(stuck));
        when(repository.find("stuck")).thenReturn(Optional.of(stuck));
        doThrow(new IllegalStateException("redis queue down")).when(queue).enqueue("stuck");

        int recovered = service.recoverStuckRunningTasks(now);

        assertThat(recovered).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(AiTaskStatus.FAILED);
        assertThat(stuck.getFinishedAtEpochMillis()).isEqualTo(now);
        assertThat(stuck.getErrorMessage()).isEqualTo("task heartbeat timeout, requeue failed");
        verify(repository, org.mockito.Mockito.times(2)).update(stuck);
        verify(repository).clearInflight("dedup-stuck", "stuck");
        verify(aiMetricsService).increment("ai.task.requeue.failed", "ai_task", true);
    }

    @Test
    void recoverStuckRunningTasksShouldFailTimedOutTaskAfterRetryLimit() throws InterruptedException {
        long now = now();
        AiTask stuck = runningTask("stuck", now - 31 * 60_000L, 3);
        when(repository.findByStatus(AiTaskStatus.RUNNING, 100)).thenReturn(java.util.List.of(stuck));
        when(repository.find("stuck")).thenReturn(Optional.of(stuck));

        int recovered = service.recoverStuckRunningTasks(now);

        assertThat(recovered).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(AiTaskStatus.FAILED);
        assertThat(stuck.getFinishedAtEpochMillis()).isEqualTo(now);
        assertThat(stuck.getErrorMessage()).isEqualTo("task heartbeat timeout, max retry exceeded");
        verify(repository).clearInflight("dedup-stuck", "stuck");
        verify(queue, never()).enqueue(anyString());
        verify(aiMetricsService).increment("ai.task.timeout.failed", "ai_task", true);
    }

    @Test
    void recoverStuckRunningTasksShouldSkipTaskStillOwnedByWorker() throws Exception {
        long now = now();
        AiTask stuck = runningTask("stuck", now - 31 * 60_000L, 1);
        when(repository.findByStatus(AiTaskStatus.RUNNING, 100)).thenReturn(java.util.List.of(stuck));
        when(taskLock.tryLock()).thenReturn(false);

        assertThat(service.recoverStuckRunningTasks(now)).isZero();

        verify(repository, never()).find("stuck");
        verify(queue, never()).enqueue(anyString());
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

    private AiTask pendingTask(String taskId, long updatedAt) {
        return AiTask.builder()
                .taskId(taskId)
                .type(AiTaskType.RAG_REBUILD_SHOP)
                .status(AiTaskStatus.PENDING)
                .dedupKey("dedup-" + taskId)
                .retryCount(0)
                .createdAtEpochMillis(updatedAt)
                .updatedAtEpochMillis(updatedAt)
                .build();
    }

    private long now() {
        return 10_000_000L;
    }
}
