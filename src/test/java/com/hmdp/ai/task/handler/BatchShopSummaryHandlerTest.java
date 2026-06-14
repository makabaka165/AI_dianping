package com.hmdp.ai.task.handler;

import com.hmdp.ai.orchestration.ShopAIOrchestrator;
import com.hmdp.ai.port.ReviewDataPort;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskType;
import com.hmdp.dto.ai.BatchSummaryResult;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.dto.ai.ShopSummaryResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchShopSummaryHandlerTest {

    @Mock
    private ReviewDataPort reviewDataPort;

    @Mock
    private ShopAIOrchestrator orchestrator;

    private BatchShopSummaryHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BatchShopSummaryHandler();
        ReflectionTestUtils.setField(handler, "reviewDataPort", reviewDataPort);
        ReflectionTestUtils.setField(handler, "orchestrator", orchestrator);
        ReflectionTestUtils.setField(handler, "defaultShopLimit", 200);
    }

    @Test
    void handleShouldContinueWhenSingleShopFailsAndReportProgress() {
        when(reviewDataPort.findActiveShopIdsForRag(3)).thenReturn(List.of(1L, 2L, 3L));
        when(orchestrator.summary(any(ShopAIRequestContext.class), any(SummaryWorkflowRequest.class)))
                .thenReturn(ShopSummaryResult.builder().shopId(1L).build());
        doThrow(new RuntimeException("boom")).when(orchestrator)
                .summary(any(ShopAIRequestContext.class), eq(SummaryWorkflowRequest.builder()
                        .shopId(2L)
                        .writeMemory(false)
                        .build()));
        java.util.List<String> progress = new java.util.ArrayList<>();

        BatchSummaryResult result = (BatchSummaryResult) handler.handle(task(params("shopLimit", 3)),
                (current, total) -> progress.add(current + "/" + total));

        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getSuccess()).isEqualTo(2);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getFailedShopIds()).containsExactly(2L);
        assertThat(progress).containsExactly("0/3", "1/3", "2/3", "3/3");
        verify(orchestrator, times(3)).summary(any(ShopAIRequestContext.class), any(SummaryWorkflowRequest.class));
        ArgumentCaptor<SummaryWorkflowRequest> requestCaptor = ArgumentCaptor.forClass(SummaryWorkflowRequest.class);
        verify(orchestrator, times(3)).summary(any(ShopAIRequestContext.class), requestCaptor.capture());
        assertThat(requestCaptor.getAllValues()).allMatch(request -> !request.isWriteMemory());
    }

    @Test
    void handleShouldUseDefaultLimitWhenParamMissing() {
        when(reviewDataPort.findActiveShopIdsForRag(200)).thenReturn(List.of());

        BatchSummaryResult result = (BatchSummaryResult) handler.handle(task(params()), (current, total) -> {
        });

        assertThat(result.getTotal()).isZero();
        verify(reviewDataPort).findActiveShopIdsForRag(200);
    }

    private AiTask task(Map<String, Object> params) {
        return AiTask.builder()
                .taskId("task-1")
                .type(AiTaskType.BATCH_SHOP_SUMMARY)
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
