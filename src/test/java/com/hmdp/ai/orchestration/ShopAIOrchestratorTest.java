package com.hmdp.ai.orchestration;

import com.hmdp.dto.ai.ShopAIIntent;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.ai.workflow.ChatWorkflow;
import com.hmdp.ai.workflow.CompareWorkflow;
import com.hmdp.ai.workflow.QAWorkflow;
import com.hmdp.ai.workflow.QualitySummaryWorkflow;
import com.hmdp.ai.workflow.RecommendWorkflow;
import com.hmdp.ai.workflow.SummaryWorkflow;
import com.hmdp.ai.workflow.request.QAWorkflowRequest;
import com.hmdp.ai.workflow.request.QualitySummaryWorkflowRequest;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.dto.ai.ShopSummaryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopAIOrchestratorTest {

    @Mock
    private ChatWorkflow chatWorkflow;

    @Mock
    private SummaryWorkflow summaryWorkflow;

    @Mock
    private QualitySummaryWorkflow qualitySummaryWorkflow;

    @Mock
    private QAWorkflow qaWorkflow;

    @Mock
    private CompareWorkflow compareWorkflow;

    @Mock
    private RecommendWorkflow recommendWorkflow;

    private ShopAIOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ShopAIOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "chatWorkflow", chatWorkflow);
        ReflectionTestUtils.setField(orchestrator, "summaryWorkflow", summaryWorkflow);
        ReflectionTestUtils.setField(orchestrator, "qualitySummaryWorkflow", qualitySummaryWorkflow);
        ReflectionTestUtils.setField(orchestrator, "qaWorkflow", qaWorkflow);
        ReflectionTestUtils.setField(orchestrator, "compareWorkflow", compareWorkflow);
        ReflectionTestUtils.setField(orchestrator, "recommendWorkflow", recommendWorkflow);
    }

    @Test
    void summaryShouldRouteToSummaryWorkflow() {
        ShopAIRequestContext context = ShopAIRequestContext.builder().build();
        SummaryWorkflowRequest request = SummaryWorkflowRequest.builder().shopId(1L).build();
        ShopSummaryResult expected = ShopSummaryResult.builder().shopId(1L).coreSummary("ok").build();
        when(summaryWorkflow.execute(context, request)).thenReturn(expected);

        ShopSummaryResult result = orchestrator.summary(context, request);

        assertThat(result).isSameAs(expected);
        assertThat(context.getIntent()).isEqualTo(ShopAIIntent.SUMMARY);
        verify(summaryWorkflow).execute(context, request);
    }

    @Test
    void askShouldRouteToQAWorkflow() {
        ShopAIRequestContext context = ShopAIRequestContext.builder().build();
        QAWorkflowRequest request = QAWorkflowRequest.builder().shopId(1L).question("服务怎么样").build();
        ShopAIResponse expected = ShopAIResponse.builder()
                .qa(ShopQAResult.builder().shopId(1L).question("服务怎么样").answer("ok").build())
                .build();
        when(qaWorkflow.execute(context, request)).thenReturn(expected);

        ShopAIResponse result = orchestrator.ask(context, request);

        assertThat(result).isSameAs(expected);
        assertThat(context.getIntent()).isEqualTo(ShopAIIntent.QA);
        verify(qaWorkflow).execute(context, request);
    }

    @Test
    void qualitySummaryShouldRouteToQualitySummaryWorkflow() {
        ShopAIRequestContext context = ShopAIRequestContext.builder().build();
        QualitySummaryWorkflowRequest request = QualitySummaryWorkflowRequest.builder()
                .shopId(1L)
                .minLiked(5)
                .limit(10)
                .build();
        ShopSummaryResult expected = ShopSummaryResult.builder().shopId(1L).coreSummary("quality").build();
        when(qualitySummaryWorkflow.execute(context, request)).thenReturn(expected);

        ShopSummaryResult result = orchestrator.qualitySummary(context, request);

        assertThat(result).isSameAs(expected);
        assertThat(context.getIntent()).isEqualTo(ShopAIIntent.SUMMARY);
        verify(qualitySummaryWorkflow).execute(context, request);
    }
}
