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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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

    @BeforeEach
    void setUp() {
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
        verify(repository).clearInflight("dedup-1");
        verify(eventPublisher, times(2)).publish(any(AiTaskEvent.class));
        verify(aiMetricsService).recordDuration(eq("ai_task"), anyLong(), eq(false));
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
        verify(repository).clearInflight("dedup-1");
        verify(eventPublisher, times(2)).publish(any(AiTaskEvent.class));
        verify(aiMetricsService).recordDuration(eq("ai_task"), anyLong(), eq(true));
        verify(aiMetricsService).increment("ai.task.count", "ai_task", true);
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
        verify(repository).clearInflight("dedup-1");
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
