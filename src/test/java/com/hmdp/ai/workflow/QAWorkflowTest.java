package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.GovernedGeneration;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityDecision;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.port.ShopDataPort;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRender;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.QAWorkflowRequest;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.EvidenceType;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.retrieval.ShopContextAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QAWorkflowTest {

    @Mock
    private ShopContextAssembler shopContextAssembler;
    @Mock
    private ShopDataPort shopDataPort;
    @Mock
    private PromptTemplateRegistry promptTemplateRegistry;
    @Mock
    private MemoryService memoryService;
    @Mock
    private ModelGateway modelGateway;
    @Mock
    private QualityGuard qualityGuard;
    @Mock
    private FallbackPolicy fallbackPolicy;
    @Mock
    private AiMetricsService aiMetricsService;

    private QAWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new QAWorkflow();
        ReflectionTestUtils.setField(workflow, "shopContextAssembler", shopContextAssembler);
        ReflectionTestUtils.setField(workflow, "shopDataPort", shopDataPort);
        ReflectionTestUtils.setField(workflow, "promptTemplateRegistry", promptTemplateRegistry);
        ReflectionTestUtils.setField(workflow, "memoryService", memoryService);
        ReflectionTestUtils.setField(workflow, "modelGateway", modelGateway);
        ReflectionTestUtils.setField(workflow, "qualityGuard", qualityGuard);
        ReflectionTestUtils.setField(workflow, "fallbackPolicy", fallbackPolicy);
        ReflectionTestUtils.setField(workflow, "governedGeneration", new GovernedGeneration());
        ReflectionTestUtils.setField(workflow, "aiMetricsService", aiMetricsService);
        lenient().when(shopDataPort.shopExists(anyLong())).thenReturn(true);
        lenient().when(shopDataPort.getReviewCount(anyLong())).thenReturn(3);
    }

    @Test
    void mismatchedShopIdShouldRepairOnceBeforeFallback() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        EvidenceItem evidence = EvidenceItem.builder()
                .id("review:10")
                .type(EvidenceType.REVIEW)
                .sourceId(10L)
                .shopId(1L)
                .snippet("服务不错")
                .build();
        ShopAnalysisContext analysisContext = ShopAnalysisContext.builder()
                .shopId(1L)
                .evidence(List.of(evidence))
                .build();
        ShopQAResult bad = ShopQAResult.builder().shopId(2L).question("服务怎么样").answer("泛泛而谈").build();
        ShopQAResult repaired = ShopQAResult.builder()
                .shopId(1L)
                .question("服务怎么样")
                .answer("repaired answer")
                .evidenceIds(List.of("review:10"))
                .insufficientEvidence(false)
                .build();
        when(memoryService.shopQAKey(1L, "u1")).thenReturn("qa-memory");
        when(memoryService.shopSummaryKey(1L, "u1")).thenReturn("summary-memory");
        when(memoryService.readSummaryMemory("summary-memory")).thenReturn("summary snapshot");
        when(shopContextAssembler.buildForShop(1L, "服务怎么样")).thenReturn(analysisContext);
        when(shopContextAssembler.toPromptBlock(analysisContext)).thenReturn("evidence block");
        when(promptTemplateRegistry.renderQA(any(ShopAIRequestContext.class), eq(1L), anyString(), anyString(), anyString()))
                .thenReturn(PromptTemplateRender.builder()
                        .content("qa prompt")
                        .version(PromptTemplateRegistry.QA_VERSION)
                        .variant("stable")
                        .build());
        when(promptTemplateRegistry.qaPrompt("服务怎么样", "summary snapshot", "evidence block")).thenReturn("qa prompt");
        when(modelGateway.generateStructuredAnswer("qa-memory", "qa prompt", 1L, "服务怎么样", analysisContext.safeEvidence()))
                .thenReturn(bad);
        when(qualityGuard.validateQA(bad, 1L, analysisContext.safeEvidence(), "ask")).thenReturn(QualityCheck.builder()
                .decision(QualityDecision.FALLBACK)
                .reason("shopId mismatch")
                .build());
        when(modelGateway.repairStructuredAnswer("qa-memory", "qa prompt", 1L, "服务怎么样", "shopId mismatch"))
                .thenReturn(repaired);
        when(qualityGuard.validateQA(repaired, 1L, analysisContext.safeEvidence(), "ask")).thenReturn(QualityCheck.builder()
                .decision(QualityDecision.PASS)
                .build());
        when(qualityGuard.postProcess("repaired answer")).thenReturn("repaired answer");

        ShopAIResponse response = workflow.execute(context, QAWorkflowRequest.builder()
                .shopId(1L)
                .question("服务怎么样")
                .build());

        assertThat(response.getQa().getAnswer()).isEqualTo("repaired answer");
        assertThat(response.getQa().getEvidenceIds()).containsExactly("review:10");
        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getMemoryId()).isEqualTo("qa-memory");
        verify(qualityGuard).validateQA(bad, 1L, analysisContext.safeEvidence(), "ask");
        verify(qualityGuard).validateQA(repaired, 1L, analysisContext.safeEvidence(), "ask");
        verify(modelGateway).repairStructuredAnswer("qa-memory", "qa prompt", 1L, "服务怎么样", "shopId mismatch");
        verify(fallbackPolicy, never()).fallbackText(anyString(), anyString(), anyString());
    }

    @Test
    void shouldReturnShopNotFoundBeforeAssemblingContext() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.shopQAKey(99L, "u1")).thenReturn("qa-memory");
        when(shopDataPort.shopExists(99L)).thenReturn(false);

        ShopAIResponse response = workflow.execute(context, QAWorkflowRequest.builder()
                .shopId(99L)
                .question("service?")
                .build());

        assertThat(response.getQa().getAnswer()).isEqualTo("店铺不存在");
        assertThat(response.getQa().getInsufficientEvidence()).isTrue();
        verify(shopContextAssembler, never()).buildForShop(anyLong(), anyString());
        verify(modelGateway, never()).generateStructuredAnswer(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void shouldReturnNoReviewsBeforeAssemblingContext() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.shopQAKey(2L, "u1")).thenReturn("qa-memory");
        when(shopDataPort.getReviewCount(2L)).thenReturn(0);

        ShopAIResponse response = workflow.execute(context, QAWorkflowRequest.builder()
                .shopId(2L)
                .question("service?")
                .build());

        assertThat(response.getQa().getAnswer()).isEqualTo("暂无评价数据");
        assertThat(response.getQa().getInsufficientEvidence()).isTrue();
        verify(shopContextAssembler, never()).buildForShop(anyLong(), anyString());
        verify(modelGateway, never()).generateStructuredAnswer(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void shouldReturnInsufficientEvidenceBeforeCallingModelWhenQAEvidenceEmpty() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.shopQAKey(1L, "u1")).thenReturn("qa-memory");
        when(shopContextAssembler.buildForShop(1L, "service?")).thenReturn(ShopAnalysisContext.builder()
                .shopId(1L)
                .evidence(List.of())
                .build());

        ShopAIResponse response = workflow.execute(context, QAWorkflowRequest.builder()
                .shopId(1L)
                .question("service?")
                .build());

        assertThat(response.getQa().getAnswer()).isEqualTo("当前评价证据不足以判断店铺1的情况。");
        assertThat(response.getQa().getInsufficientEvidence()).isTrue();
        assertThat(response.getConfidence()).isEqualTo(0.2);
        verify(modelGateway, never()).generateStructuredAnswer(anyString(), anyString(), anyLong(), anyString(), any());
        verify(memoryService, never()).readSummaryMemory(anyString());
    }

    @Test
    void streamPlanShouldUseAskAnalysisTypeForStructuredQa() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        EvidenceItem evidence = EvidenceItem.builder()
                .id("review:10")
                .type(EvidenceType.REVIEW)
                .sourceId(10L)
                .shopId(1L)
                .snippet("service is stable")
                .build();
        ShopAnalysisContext analysisContext = ShopAnalysisContext.builder()
                .shopId(1L)
                .evidence(List.of(evidence))
                .build();
        when(memoryService.shopQAKey(1L, "u1")).thenReturn("qa-memory");
        when(memoryService.shopSummaryKey(1L, "u1")).thenReturn("summary-memory");
        when(memoryService.readSummaryMemory("summary-memory")).thenReturn("summary snapshot");
        when(shopContextAssembler.buildForShop(1L, "service?")).thenReturn(analysisContext);
        when(shopContextAssembler.toPromptBlock(analysisContext)).thenReturn("evidence block");
        when(promptTemplateRegistry.renderQA(context, 1L, "service?", "summary snapshot", "evidence block"))
                .thenReturn(PromptTemplateRender.builder()
                        .content("qa prompt")
                        .version(PromptTemplateRegistry.QA_VERSION)
                        .variant("stable")
                        .build());

        StreamWorkflowPlan plan = workflow.prepareStreamPlan(context, QAWorkflowRequest.builder()
                .shopId(1L)
                .question("service?")
                .build());

        assertThat(plan.getAnalysisType()).isEqualTo("ask");
        assertThat(plan.isStructuredOutput()).isTrue();
        assertThat(plan.getExpectedShopId()).isEqualTo(1L);
        assertThat(plan.getExpectedQuestion()).isEqualTo("service?");
    }
}
