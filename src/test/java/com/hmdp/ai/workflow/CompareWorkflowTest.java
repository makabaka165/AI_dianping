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
import com.hmdp.ai.workflow.request.CompareWorkflowRequest;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.EvidenceType;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.dto.ai.ShopCompareResult;
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
class CompareWorkflowTest {

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

    private CompareWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new CompareWorkflow();
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
    void qualityFailureShouldRepairWithCompareSpecificFallbackKey() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        ShopAnalysisContext first = contextWithEvidence(1L, 11L);
        ShopAnalysisContext second = contextWithEvidence(2L, 22L);
        List<EvidenceItem> evidence = List.of(first.getEvidence().get(0), second.getEvidence().get(0));
        ShopCompareResult bad = ShopCompareResult.builder()
                .shopId1(1L).shopId2(2L).aspect("服务").conclusion("bad")
                .winnerByAspect("BAD").shop1Score(50).shop2Score(50).build();
        ShopCompareResult repaired = ShopCompareResult.builder()
                .shopId1(1L)
                .shopId2(2L)
                .aspect("服务")
                .conclusion("repaired comparison")
                .winnerByAspect(ShopCompareResult.SHOP_1)
                .shop1Score(80)
                .shop2Score(65)
                .evidenceIds(List.of("review:11", "review:22"))
                .build();
        when(memoryService.shopCompareKey("u1", "s1")).thenReturn("compare-memory");
        when(shopContextAssembler.buildForCompare(1L, "店铺对比", "服务")).thenReturn(first);
        when(shopContextAssembler.buildForCompare(2L, "店铺对比", "服务")).thenReturn(second);
        when(shopContextAssembler.toPromptBlock(first)).thenReturn("first block");
        when(shopContextAssembler.toPromptBlock(second)).thenReturn("second block");
        when(promptTemplateRegistry.renderCompare(any(ShopAIRequestContext.class), eq(1L), eq(2L), anyString(), anyString(), anyString()))
                .thenReturn(PromptTemplateRender.builder()
                        .content("compare prompt")
                        .version(PromptTemplateRegistry.COMPARE_VERSION)
                        .variant("stable")
                        .build());
        when(promptTemplateRegistry.comparePrompt("服务", "first block", "second block")).thenReturn("compare prompt");
        when(modelGateway.generateStructuredComparison("compare-memory", "compare prompt", 1L, 2L, "服务", evidence))
                .thenReturn(bad);
        when(qualityGuard.validateCompare(bad, 1L, 2L, evidence, "compare")).thenReturn(QualityCheck.builder()
                .decision(QualityDecision.FALLBACK)
                .reason("too generic")
                .build());
        when(modelGateway.repairStructuredComparison("compare-memory", "compare prompt", 1L, 2L, "服务", "too generic"))
                .thenReturn(repaired);
        when(qualityGuard.validateCompare(repaired, 1L, 2L, evidence, "compare")).thenReturn(QualityCheck.builder()
                .decision(QualityDecision.PASS)
                .build());
        when(qualityGuard.postProcess("repaired comparison")).thenReturn("repaired comparison");

        ShopAIResponse response = workflow.execute(context, CompareWorkflowRequest.builder()
                .shopId1(1L)
                .shopId2(2L)
                .aspect("服务")
                .build());

        assertThat(response.getCompare().getConclusion()).isEqualTo("repaired comparison");
        assertThat(response.getCompare().getWinnerByAspect()).isEqualTo(ShopCompareResult.SHOP_1);
        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getMemoryId()).isEqualTo("compare-memory");
        verify(modelGateway).repairStructuredComparison("compare-memory", "compare prompt", 1L, 2L, "服务", "too generic");
        verify(fallbackPolicy, never()).fallbackText(anyString(), anyString(), anyString());
    }

    @Test
    void shouldExplainEachUnavailableShopBeforeAssemblingContext() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.shopCompareKey("u1", "s1")).thenReturn("compare-memory");
        when(shopDataPort.getReviewCount(1L)).thenReturn(0);
        when(shopDataPort.shopExists(2L)).thenReturn(false);

        ShopAIResponse response = workflow.execute(context, CompareWorkflowRequest.builder()
                .shopId1(1L)
                .shopId2(2L)
                .aspect("service")
                .build());

        assertThat(response.getCompare().getConclusion())
                .contains("店铺1暂无评价数据")
                .contains("店铺2不存在");
        assertThat(response.getCompare().getWinnerByAspect()).isEqualTo(ShopCompareResult.INSUFFICIENT);
        assertThat(response.getEvidence()).isEmpty();
        verify(shopContextAssembler, never()).buildForCompare(anyLong(), anyString(), any());
        verify(modelGateway, never()).generateStructuredComparison(anyString(), anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void shouldReturnInsufficientCompareBeforeCallingModelWhenEvidenceEmpty() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.shopCompareKey("u1", "s1")).thenReturn("compare-memory");
        when(shopContextAssembler.buildForCompare(1L, "店铺对比", "service"))
                .thenReturn(ShopAnalysisContext.builder().shopId(1L).evidence(List.of()).build());
        when(shopContextAssembler.buildForCompare(2L, "店铺对比", "service"))
                .thenReturn(ShopAnalysisContext.builder().shopId(2L).evidence(List.of()).build());

        ShopAIResponse response = workflow.execute(context, CompareWorkflowRequest.builder()
                .shopId1(1L)
                .shopId2(2L)
                .aspect("service")
                .build());

        assertThat(response.getCompare().getConclusion()).isEqualTo("当前评价证据不足以判断两家店铺的对比表现。");
        assertThat(response.getCompare().getWinnerByAspect()).isEqualTo(ShopCompareResult.INSUFFICIENT);
        assertThat(response.getConfidence()).isEqualTo(0.2);
        verify(modelGateway, never()).generateStructuredComparison(anyString(), anyString(), anyLong(), anyLong(), any(), any());
    }

    private ShopAnalysisContext contextWithEvidence(Long shopId, Long blogId) {
        return ShopAnalysisContext.builder()
                .shopId(shopId)
                .evidence(List.of(EvidenceItem.builder()
                        .id(EvidenceItem.reviewId(blogId))
                        .type(EvidenceType.REVIEW)
                        .sourceId(blogId)
                        .shopId(shopId)
                        .snippet("服务不错")
                        .build()))
                .build();
    }
}
