package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.GovernedGeneration;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityDecision;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRender;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.RecommendWorkflowRequest;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopRecommendationItem;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.ShopReviewEvidenceRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendWorkflowTest {

    @Mock
    private ShopMapper shopMapper;
    @Mock
    private ShopReviewEvidenceRetriever evidenceRetriever;
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

    private RecommendWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new RecommendWorkflow();
        ReflectionTestUtils.setField(workflow, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(workflow, "evidenceRetriever", evidenceRetriever);
        ReflectionTestUtils.setField(workflow, "promptTemplateRegistry", promptTemplateRegistry);
        ReflectionTestUtils.setField(workflow, "memoryService", memoryService);
        ReflectionTestUtils.setField(workflow, "modelGateway", modelGateway);
        ReflectionTestUtils.setField(workflow, "qualityGuard", qualityGuard);
        ReflectionTestUtils.setField(workflow, "fallbackPolicy", fallbackPolicy);
        ReflectionTestUtils.setField(workflow, "governedGeneration", new GovernedGeneration());
        ReflectionTestUtils.setField(workflow, "aiMetricsService", aiMetricsService);
    }

    @Test
    void qualityFailureShouldRepairWithRecommendSpecificFallbackKey() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        Shop shop = new Shop()
                .setId(1L)
                .setName("约会餐厅")
                .setArea("商圈")
                .setAvgPrice(80L)
                .setSold(100)
                .setComments(30)
                .setScore(45);
        ShopRecommendResult bad = ShopRecommendResult.builder()
                .userPreference("适合约会")
                .category("餐厅")
                .items(Collections.emptyList())
                .build();
        ShopRecommendResult repaired = ShopRecommendResult.builder()
                .userPreference("适合约会")
                .category("餐厅")
                .message("repaired recommendation")
                .items(List.of(ShopRecommendationItem.builder()
                        .rank(1)
                        .shopId(1L)
                        .shopName("约会餐厅")
                        .reason("repaired recommendation")
                        .evidenceIds(List.of("shop_profile:1"))
                        .confidence(0.7)
                        .build()))
                .build();
        when(memoryService.shopRecommendKey("u1")).thenReturn("recommend-memory");
        when(shopMapper.selectRecommendCandidates("餐厅", 1)).thenReturn(List.of(shop));
        when(evidenceRetriever.retrieve(1L, "适合约会", "餐厅", 2)).thenReturn(Collections.emptyList());
        when(promptTemplateRegistry.renderRecommend(any(ShopAIRequestContext.class), eq("适合约会"), eq("餐厅"), eq(1), anyString()))
                .thenReturn(PromptTemplateRender.builder()
                        .content("recommend prompt")
                        .version(PromptTemplateRegistry.RECOMMEND_VERSION)
                        .variant("stable")
                        .build());
        when(promptTemplateRegistry.recommendPrompt(eq("适合约会"), eq("餐厅"), eq(1), anyString()))
                .thenReturn("recommend prompt");
        when(modelGateway.generateStructuredRecommendation(eq("recommend-memory"), eq("recommend prompt"), eq("适合约会"),
                eq("餐厅"), eq(List.of(shop)), any())).thenReturn(bad);
        when(qualityGuard.validateRecommend(eq(bad), eq(Set.of(1L)), any(), eq("recommend"))).thenReturn(QualityCheck.builder()
                .decision(QualityDecision.FALLBACK)
                .reason("too generic")
                .build());
        when(modelGateway.repairStructuredRecommendation("recommend-memory", "recommend prompt", "适合约会", "餐厅",
                List.of(shop), "too generic")).thenReturn(repaired);
        when(qualityGuard.validateRecommend(eq(repaired), eq(Set.of(1L)), any(), eq("recommend"))).thenReturn(QualityCheck.builder()
                .decision(QualityDecision.PASS)
                .build());

        ShopAIResponse response = workflow.execute(context, RecommendWorkflowRequest.builder()
                .userPreference("适合约会")
                .category("餐厅")
                .limit(1)
                .build());

        assertThat(response.getRecommend().getMessage()).isEqualTo("repaired recommendation");
        assertThat(response.getRecommend().getItems()).hasSize(1);
        assertThat(response.getEvidence()).extracting("id").contains("shop_profile:1");
        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getMemoryId()).isEqualTo("recommend-memory");
        verify(modelGateway).repairStructuredRecommendation("recommend-memory", "recommend prompt", "适合约会", "餐厅",
                List.of(shop), "too generic");
        verify(fallbackPolicy, never()).fallbackText(anyString(), anyString(), anyString());
    }
}
