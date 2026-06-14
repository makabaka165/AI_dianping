package com.hmdp.ai.task;

import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.retrieval.ShopReviewVectorIndexService;
import com.hmdp.dto.ai.AiTask;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTaskWorkerTest {

    @Mock
    private AiTaskQueue queue;

    @Mock
    private AiTaskRepository repository;

    @Mock
    private ShopReviewVectorIndexService vectorIndexService;

    @Mock
    private AiMetricsService aiMetricsService;

    private AiTaskWorker worker;

    @BeforeEach
    void setUp() {
        worker = new AiTaskWorker();
        ReflectionTestUtils.setField(worker, "queue", queue);
        ReflectionTestUtils.setField(worker, "repository", repository);
        ReflectionTestUtils.setField(worker, "shopReviewVectorIndexService", vectorIndexService);
        ReflectionTestUtils.setField(worker, "aiMetricsService", aiMetricsService);
    }

    @Test
    void processShouldMarkTaskSuccessAndStoreResult() {
        AiTask task = task(AiTaskType.RAG_REBUILD_SHOP, params("shopId", 7, "limit", 20));
        ShopRagRebuildResult result = ShopRagRebuildResult.builder()
                .shopId(7L)
                .indexed(3)
                .skipped(0)
                .failed(0)
                .durationMs(11L)
                .message("ok")
                .build();
        when(repository.find("task-1")).thenReturn(Optional.of(task));
        when(vectorIndexService.rebuildShop(7L, 20)).thenReturn(result);

        worker.process("task-1");

        ArgumentCaptor<AiTask> updates = ArgumentCaptor.forClass(AiTask.class);
        verify(repository, org.mockito.Mockito.times(2)).update(updates.capture());
        AiTask finalTask = updates.getAllValues().get(1);
        assertThat(finalTask.getStatus()).isEqualTo(AiTaskStatus.SUCCESS);
        assertThat(finalTask.getResult()).isSameAs(result);
        assertThat(finalTask.getErrorMessage()).isNull();
        verify(repository).clearInflight("dedup-1");
        verify(aiMetricsService).recordDuration(eq("ai_task"), anyLong(), eq(false));
        verify(aiMetricsService).increment("ai.task.count", "ai_task", false);
    }

    @Test
    void processShouldMarkTaskFailedWhenRebuildThrows() {
        AiTask task = task(AiTaskType.RAG_REBUILD_ALL, params("shopLimit", 10, "perShopLimit", 20));
        when(repository.find("task-1")).thenReturn(Optional.of(task));
        when(vectorIndexService.rebuildAll(10, 20)).thenThrow(new RuntimeException("boom"));

        worker.process("task-1");

        ArgumentCaptor<AiTask> updates = ArgumentCaptor.forClass(AiTask.class);
        verify(repository, org.mockito.Mockito.times(2)).update(updates.capture());
        AiTask finalTask = updates.getAllValues().get(1);
        assertThat(finalTask.getStatus()).isEqualTo(AiTaskStatus.FAILED);
        assertThat(finalTask.getErrorMessage()).isEqualTo("boom");
        verify(repository).clearInflight("dedup-1");
        verify(aiMetricsService).recordDuration(eq("ai_task"), anyLong(), eq(true));
        verify(aiMetricsService).increment("ai.task.count", "ai_task", true);
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
