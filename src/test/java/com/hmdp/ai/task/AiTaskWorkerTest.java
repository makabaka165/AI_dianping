package com.hmdp.ai.task;

import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskEvent;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTaskWorkerTest {

    @Mock
    private AiTaskQueue queue;

    @Mock
    private AiTaskRepository repository;

    @Mock
    private AiMetricsService aiMetricsService;

    @Mock
    private AiTaskEventPublisher eventPublisher;

    @Mock
    private RLock taskLock;

    @BeforeEach
    void setUp() throws InterruptedException {
        lenient().when(repository.executionLock(any(String.class))).thenReturn(taskLock);
        lenient().when(taskLock.tryLock(1L, TimeUnit.SECONDS)).thenReturn(true);
        lenient().when(taskLock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void processShouldMarkTaskSuccessAndStoreResult() throws Exception {
        AiTask task = task(AiTaskType.RAG_REBUILD_SHOP, params("shopId", 7, "limit", 20));
        AiTaskHandler handler = handler(AiTaskType.RAG_REBUILD_SHOP);
        AiTaskWorker worker = workerWith(handler);
        ShopRagRebuildResult result = ShopRagRebuildResult.builder()
                .shopId(7L)
                .indexed(3)
                .skipped(0)
                .failed(0)
                .durationMs(11L)
                .message("ok")
                .build();
        when(repository.find("task-1")).thenReturn(Optional.of(task));
        when(handler.handle(eq(task), any(AiTaskProgressReporter.class))).thenReturn(result);

        worker.process("task-1");

        ArgumentCaptor<AiTask> updates = ArgumentCaptor.forClass(AiTask.class);
        verify(repository, times(2)).update(updates.capture());
        AiTask finalTask = updates.getAllValues().get(1);
        assertThat(finalTask.getStatus()).isEqualTo(AiTaskStatus.SUCCESS);
        assertThat(finalTask.getResult()).isSameAs(result);
        assertThat(finalTask.getErrorMessage()).isNull();
        assertThat(finalTask.getStartedAtEpochMillis()).isPositive();
        assertThat(finalTask.getHeartbeatAtEpochMillis()).isPositive();
        assertThat(finalTask.getFinishedAtEpochMillis()).isPositive();
        verify(repository).clearInflight("dedup-1", "task-1");
        verify(eventPublisher, times(2)).publish(any(AiTaskEvent.class));
        verify(aiMetricsService).recordDuration(eq("ai_task"), anyLong(), eq(false));
        verify(aiMetricsService).increment("ai.task.count", "ai_task", false);
    }

    @Test
    void processShouldKeepSuccessWhenEventAndMetricsPublishingFail() throws Exception {
        AiTask task = task(AiTaskType.RAG_REBUILD_SHOP, params("shopId", 7));
        AiTaskHandler handler = handler(AiTaskType.RAG_REBUILD_SHOP);
        AiTaskWorker worker = workerWith(handler);
        when(repository.find("task-1")).thenReturn(Optional.of(task));
        when(handler.handle(eq(task), any(AiTaskProgressReporter.class))).thenReturn("ok");
        doThrow(new IllegalStateException("topic down")).when(eventPublisher).publish(any(AiTaskEvent.class));
        doThrow(new IllegalStateException("metrics down"))
                .when(aiMetricsService).recordDuration(eq("ai_task"), anyLong(), eq(false));

        worker.process("task-1");

        assertThat(task.getStatus()).isEqualTo(AiTaskStatus.SUCCESS);
        assertThat(task.getResult()).isEqualTo("ok");
        verify(repository).clearInflight("dedup-1", "task-1");
        verify(aiMetricsService).increment("ai.task.count", "ai_task", false);
    }

    @Test
    void processShouldMarkTaskFailedWhenHandlerThrows() throws Exception {
        AiTask task = task(AiTaskType.RAG_REBUILD_ALL, params("shopLimit", 10, "perShopLimit", 20));
        AiTaskHandler handler = handler(AiTaskType.RAG_REBUILD_ALL);
        AiTaskWorker worker = workerWith(handler);
        when(repository.find("task-1")).thenReturn(Optional.of(task));
        when(handler.handle(eq(task), any(AiTaskProgressReporter.class))).thenThrow(new RuntimeException("boom"));

        worker.process("task-1");

        ArgumentCaptor<AiTask> updates = ArgumentCaptor.forClass(AiTask.class);
        verify(repository, times(2)).update(updates.capture());
        AiTask finalTask = updates.getAllValues().get(1);
        assertThat(finalTask.getStatus()).isEqualTo(AiTaskStatus.FAILED);
        assertThat(finalTask.getErrorMessage()).isEqualTo("boom");
        assertThat(finalTask.getStartedAtEpochMillis()).isPositive();
        assertThat(finalTask.getHeartbeatAtEpochMillis()).isPositive();
        assertThat(finalTask.getFinishedAtEpochMillis()).isPositive();
        verify(repository).clearInflight("dedup-1", "task-1");
        verify(eventPublisher, times(2)).publish(any(AiTaskEvent.class));
        verify(aiMetricsService).recordDuration(eq("ai_task"), anyLong(), eq(true));
        verify(aiMetricsService).increment("ai.task.count", "ai_task", true);
    }

    @Test
    void processShouldKeepInflightMarkerWhenNonExceptionFailureLeavesTaskRunning() throws Exception {
        AiTask task = task(AiTaskType.RAG_REBUILD_ALL, params("shopLimit", 10));
        AiTaskHandler handler = handler(AiTaskType.RAG_REBUILD_ALL);
        AiTaskWorker worker = workerWith(handler);
        when(repository.find("task-1")).thenReturn(Optional.of(task));
        when(handler.handle(eq(task), any(AiTaskProgressReporter.class)))
                .thenThrow(new AssertionError("worker aborted"));

        assertThatThrownBy(() -> worker.process("task-1"))
                .isInstanceOf(AssertionError.class)
                .hasMessage("worker aborted");

        assertThat(task.getStatus()).isEqualTo(AiTaskStatus.RUNNING);
        verify(repository, times(2)).update(task);
        verify(repository, never()).clearInflight("dedup-1", "task-1");
        verify(taskLock).unlock();
    }

    @Test
    void processShouldPersistAndPublishProgressEventsFromHandler() throws Exception {
        AiTask task = task(AiTaskType.RAG_REBUILD_ALL, params("shopLimit", 10, "perShopLimit", 20));
        AiTaskHandler handler = handler(AiTaskType.RAG_REBUILD_ALL);
        AiTaskWorker worker = workerWith(handler);
        ShopRagRebuildResult result = ShopRagRebuildResult.builder()
                .indexed(6)
                .skipped(0)
                .failed(0)
                .durationMs(12L)
                .message("ok")
                .build();
        when(repository.find("task-1")).thenReturn(Optional.of(task));
        doAnswer(invocation -> {
            AiTaskProgressReporter reporter = invocation.getArgument(1);
            reporter.report(1, 2);
            reporter.report(2, 2);
            return result;
        }).when(handler).handle(eq(task), any(AiTaskProgressReporter.class));

        worker.process("task-1");

        verify(repository, times(4)).update(any(AiTask.class));
        assertThat(task.getStatus()).isEqualTo(AiTaskStatus.SUCCESS);
        assertThat(task.getProgressCurrent()).isEqualTo(2);
        assertThat(task.getProgressTotal()).isEqualTo(2);
        assertThat(task.getHeartbeatAtEpochMillis()).isPositive();
        ArgumentCaptor<AiTaskEvent> events = ArgumentCaptor.forClass(AiTaskEvent.class);
        verify(eventPublisher, times(4)).publish(events.capture());
        assertThat(events.getAllValues()).extracting(AiTaskEvent::getStatus)
                .containsExactly(AiTaskStatus.RUNNING, AiTaskStatus.RUNNING,
                        AiTaskStatus.RUNNING, AiTaskStatus.SUCCESS);
        assertThat(events.getAllValues().get(1).getProgressCurrent()).isEqualTo(1);
        assertThat(events.getAllValues().get(1).getProgressTotal()).isEqualTo(2);
        assertThat(events.getAllValues().get(2).getProgressCurrent()).isEqualTo(2);
        assertThat(events.getAllValues().get(2).getProgressTotal()).isEqualTo(2);
    }

    @Test
    void processShouldFailWhenHandlerMissing() {
        AiTask task = task(AiTaskType.BATCH_SHOP_SUMMARY, params("shopLimit", 10));
        AiTaskWorker worker = workerWith(handler(AiTaskType.RAG_REBUILD_SHOP));
        when(repository.find("task-1")).thenReturn(Optional.of(task));

        worker.process("task-1");

        assertThat(task.getStatus()).isEqualTo(AiTaskStatus.FAILED);
        assertThat(task.getErrorMessage()).contains("Unsupported AI task type");
        verify(repository, times(2)).update(any(AiTask.class));
        verify(repository).clearInflight("dedup-1", "task-1");
    }

    @Test
    void processShouldSkipTaskThatIsAlreadyTerminal() throws Exception {
        AiTask task = task(AiTaskType.RAG_REBUILD_SHOP, params("shopId", 7));
        task.setStatus(AiTaskStatus.SUCCESS);
        AiTaskHandler handler = handler(AiTaskType.RAG_REBUILD_SHOP);
        AiTaskWorker worker = workerWith(handler);
        when(repository.find("task-1")).thenReturn(Optional.of(task));

        worker.process("task-1");

        verify(handler, never()).handle(any(AiTask.class), any(AiTaskProgressReporter.class));
        verify(repository, never()).update(any(AiTask.class));
    }

    @Test
    void processShouldRequeueDuplicateEntryWhileExecutionLockIsHeld() throws Exception {
        AiTaskHandler handler = handler(AiTaskType.RAG_REBUILD_SHOP);
        AiTaskWorker worker = workerWith(handler);
        when(taskLock.tryLock(1L, TimeUnit.SECONDS)).thenReturn(false);

        worker.process("task-1");

        verify(queue).enqueue("task-1");
        verify(repository, never()).find("task-1");
        verify(handler, never()).handle(any(AiTask.class), any(AiTaskProgressReporter.class));
    }

    @Test
    void processShouldRequeueWhenTaskLookupFailsBeforeExecution() throws Exception {
        AiTaskWorker worker = workerWith(handler(AiTaskType.RAG_REBUILD_SHOP));
        when(repository.find("task-1")).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> worker.process("task-1"))
                .isInstanceOf(IllegalStateException.class);

        verify(queue).enqueue("task-1");
        verify(taskLock).unlock();
    }

    @Test
    void constructorShouldRejectDuplicateHandlerTypes() {
        AiTaskHandler first = handler(AiTaskType.RAG_REBUILD_ALL);
        AiTaskHandler second = handler(AiTaskType.RAG_REBUILD_ALL);

        assertThatThrownBy(() -> new AiTaskWorker(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate AI task handler type");
    }

    private AiTaskHandler handler(AiTaskType type) {
        AiTaskHandler handler = org.mockito.Mockito.mock(AiTaskHandler.class);
        when(handler.type()).thenReturn(type);
        return handler;
    }

    private AiTaskWorker workerWith(AiTaskHandler... handlers) {
        AiTaskWorker worker = new AiTaskWorker(List.of(handlers));
        ReflectionTestUtils.setField(worker, "queue", queue);
        ReflectionTestUtils.setField(worker, "repository", repository);
        ReflectionTestUtils.setField(worker, "aiMetricsService", aiMetricsService);
        ReflectionTestUtils.setField(worker, "eventPublisher", eventPublisher);
        return worker;
    }

    private AiTask task(AiTaskType type, Map<String, Object> params) {
        return AiTask.builder()
                .taskId("task-1")
                .type(type)
                .status(AiTaskStatus.PENDING)
                .dedupKey("dedup-1")
                .params(params)
                .build();
    }

    private Map<String, Object> params(Object... values) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            params.put((String) values[i], values[i + 1]);
        }
        return params;
    }
}
