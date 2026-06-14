package com.hmdp.ai.task.handler;

import com.hmdp.ai.retrieval.RebuildProgressListener;
import com.hmdp.ai.retrieval.ShopReviewVectorIndexService;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskType;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRebuildHandlerTest {

    @Mock
    private ShopReviewVectorIndexService vectorIndexService;

    @Test
    void allHandlerShouldBridgeRebuildProgress() {
        RagRebuildAllHandler handler = new RagRebuildAllHandler();
        ReflectionTestUtils.setField(handler, "shopReviewVectorIndexService", vectorIndexService);
        ShopRagRebuildResult result = ShopRagRebuildResult.builder().indexed(3).build();
        when(vectorIndexService.rebuildAll(eq(10), eq(20), any(RebuildProgressListener.class))).thenReturn(result);
        AiTask task = task(AiTaskType.RAG_REBUILD_ALL, params("shopLimit", 10, "perShopLimit", 20));
        java.util.List<String> progress = new java.util.ArrayList<>();

        Object actual = handler.handle(task, (current, total) -> progress.add(current + "/" + total));

        assertThat(actual).isSameAs(result);
        ArgumentCaptor<RebuildProgressListener> captor = ArgumentCaptor.forClass(RebuildProgressListener.class);
        verify(vectorIndexService).rebuildAll(eq(10), eq(20), captor.capture());
        captor.getValue().onProgress(1, 2);
        assertThat(progress).containsExactly("1/2");
    }

    @Test
    void shopHandlerShouldCallRebuildShop() {
        RagRebuildShopHandler handler = new RagRebuildShopHandler();
        ReflectionTestUtils.setField(handler, "shopReviewVectorIndexService", vectorIndexService);
        ShopRagRebuildResult result = ShopRagRebuildResult.builder().shopId(7L).indexed(1).build();
        when(vectorIndexService.rebuildShop(7L, 20)).thenReturn(result);

        Object actual = handler.handle(task(AiTaskType.RAG_REBUILD_SHOP, params("shopId", 7, "limit", 20)),
                (current, total) -> {
                });

        assertThat(actual).isSameAs(result);
        verify(vectorIndexService).rebuildShop(7L, 20);
    }

    private AiTask task(AiTaskType type, Map<String, Object> params) {
        return AiTask.builder()
                .taskId("task-1")
                .type(type)
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
