package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.fallback.FallbackReason;
import com.hmdp.ai.guard.GovernedGeneration;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityDecision;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.ai.port.ShopDataPort;
import com.hmdp.ai.prompt.PromptTemplateRender;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.RecommendWorkflowRequest;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopRecommendationItem;
import com.hmdp.dto.ai.ShopView;
import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.retrieval.ShopReviewEvidenceRetriever;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendWorkflowTest {

    @Mock
    private ShopDataPort shopDataPort;
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
        ReflectionTestUtils.setField(workflow, "shopDataPort", shopDataPort);
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
        ShopView shop = ShopView.builder()
                .id(1L)
                .name("约会餐厅")
                .area("商圈")
                .avgPrice(80L)
                .sold(100)
                .comments(30)
                .score(45)
                .build();
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
        when(shopDataPort.findRecommendCandidates("餐厅", 20)).thenReturn(List.of(shop));
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
        verify(shopDataPort).findRecommendCandidates("餐厅", 20);
        verify(modelGateway).repairStructuredRecommendation("recommend-memory", "recommend prompt", "适合约会", "餐厅",
                List.of(shop), "too generic");
        verify(fallbackPolicy, never()).fallbackText(anyString(), anyString(), anyString());
    }

    @Test
    void shouldUseExpandedCandidatesButReturnRequestedLimit() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        List<ShopView> candidates = List.of(shop(1L), shop(2L), shop(3L));
        ShopRecommendResult generated = ShopRecommendResult.builder()
                .userPreference("quiet")
                .category("food")
                .message("generated")
                .items(List.of(item(1, 1L), item(2, 2L), item(3, 3L)))
                .build();

        when(memoryService.shopRecommendKey("u1")).thenReturn("recommend-memory");
        when(shopDataPort.findRecommendCandidates("food", 20)).thenReturn(candidates);
        when(evidenceRetriever.retrieve(anyLong(), eq("quiet"), eq("food"), eq(2))).thenReturn(Collections.emptyList());
        when(promptTemplateRegistry.renderRecommend(any(ShopAIRequestContext.class), eq("quiet"), eq("food"), eq(2), anyString()))
                .thenReturn(PromptTemplateRender.builder()
                        .content("recommend prompt")
                        .version(PromptTemplateRegistry.RECOMMEND_VERSION)
                        .variant("stable")
                        .build());
        when(modelGateway.generateStructuredRecommendation(eq("recommend-memory"), eq("recommend prompt"), eq("quiet"),
                eq("food"), eq(candidates), any())).thenReturn(generated);
        when(qualityGuard.validateRecommend(eq(generated), eq(Set.of(1L, 2L, 3L)), any(), eq("recommend")))
                .thenReturn(QualityCheck.builder()
                        .decision(QualityDecision.PASS)
                        .build());

        ShopAIResponse response = workflow.execute(context, RecommendWorkflowRequest.builder()
                .userPreference("quiet")
                .category("food")
                .limit(2)
                .build());

        assertThat(response.getRecommend().getItems()).extracting("shopId").containsExactly(1L, 2L);
        assertThat(response.getRecommend().getItems()).extracting("rank").containsExactly(1, 2);
        verify(shopDataPort).findRecommendCandidates("food", 20);
        verify(promptTemplateRegistry).renderRecommend(any(ShopAIRequestContext.class), eq("quiet"), eq("food"), eq(2), anyString());
    }

    @Test
    void shouldUseRequestedLimitTimesThreeWhenAboveTwentyCandidates() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        List<ShopView> candidates = java.util.stream.LongStream.rangeClosed(1, 30)
                .mapToObj(this::shop)
                .collect(java.util.stream.Collectors.toList());
        ShopRecommendResult generated = ShopRecommendResult.builder()
                .userPreference("quiet")
                .category("food")
                .message("generated")
                .items(List.of(item(1, 1L)))
                .build();

        when(memoryService.shopRecommendKey("u1")).thenReturn("recommend-memory");
        when(shopDataPort.findRecommendCandidates("food", 30)).thenReturn(candidates);
        when(evidenceRetriever.retrieve(anyLong(), eq("quiet"), eq("food"), eq(2))).thenReturn(Collections.emptyList());
        when(promptTemplateRegistry.renderRecommend(any(ShopAIRequestContext.class), eq("quiet"), eq("food"), eq(10), anyString()))
                .thenReturn(PromptTemplateRender.builder()
                        .content("recommend prompt")
                        .version(PromptTemplateRegistry.RECOMMEND_VERSION)
                        .variant("stable")
                        .build());
        when(modelGateway.generateStructuredRecommendation(eq("recommend-memory"), eq("recommend prompt"), eq("quiet"),
                eq("food"), eq(candidates), any())).thenReturn(generated);
        when(qualityGuard.validateRecommend(eq(generated), any(), any(), eq("recommend")))
                .thenReturn(QualityCheck.builder().decision(QualityDecision.PASS).build());

        ShopAIResponse response = workflow.execute(context, RecommendWorkflowRequest.builder()
                .userPreference("quiet")
                .category("food")
                .limit(10)
                .build());

        assertThat(response.getRecommend().getItems()).hasSize(1);
        verify(shopDataPort).findRecommendCandidates("food", 30);
    }

    @Test
    void shouldRerankRecommendItemsAfterTruncation() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        List<ShopView> candidates = List.of(shop(1L), shop(2L), shop(3L));
        ShopRecommendResult generated = ShopRecommendResult.builder()
                .userPreference("quiet")
                .category("food")
                .message("generated")
                .items(List.of(item(10, 1L), item(20, 2L), item(30, 3L)))
                .build();

        when(memoryService.shopRecommendKey("u1")).thenReturn("recommend-memory");
        when(shopDataPort.findRecommendCandidates("food", 20)).thenReturn(candidates);
        when(evidenceRetriever.retrieve(anyLong(), eq("quiet"), eq("food"), eq(2))).thenReturn(Collections.emptyList());
        when(promptTemplateRegistry.renderRecommend(any(ShopAIRequestContext.class), eq("quiet"), eq("food"), eq(2), anyString()))
                .thenReturn(PromptTemplateRender.builder()
                        .content("recommend prompt")
                        .version(PromptTemplateRegistry.RECOMMEND_VERSION)
                        .variant("stable")
                        .build());
        when(modelGateway.generateStructuredRecommendation(eq("recommend-memory"), eq("recommend prompt"), eq("quiet"),
                eq("food"), eq(candidates), any())).thenReturn(generated);
        when(qualityGuard.validateRecommend(eq(generated), eq(Set.of(1L, 2L, 3L)), any(), eq("recommend")))
                .thenReturn(QualityCheck.builder().decision(QualityDecision.PASS).build());

        ShopAIResponse response = workflow.execute(context, RecommendWorkflowRequest.builder()
                .userPreference("quiet")
                .category("food")
                .limit(2)
                .build());

        assertThat(response.getRecommend().getItems()).extracting("shopId").containsExactly(1L, 2L);
        assertThat(response.getRecommend().getItems()).extracting("rank").containsExactly(1, 2);
    }

    @Test
    void shouldFallbackWhenRecommendationItemsAndMessageRemainEmptyAfterRepair() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        List<ShopView> candidates = List.of(shop(1L), shop(2L));
        ShopRecommendResult empty = ShopRecommendResult.builder()
                .userPreference("quiet")
                .category("food")
                .items(Collections.emptyList())
                .message(" ")
                .build();
        ShopRecommendResult fallback = ShopRecommendResult.builder()
                .userPreference("quiet")
                .category("food")
                .message("fallback")
                .items(List.of(item(1, 1L)))
                .build();

        when(memoryService.shopRecommendKey("u1")).thenReturn("recommend-memory");
        when(shopDataPort.findRecommendCandidates("food", 20)).thenReturn(candidates);
        when(evidenceRetriever.retrieve(anyLong(), eq("quiet"), eq("food"), eq(2))).thenReturn(Collections.emptyList());
        when(promptTemplateRegistry.renderRecommend(any(ShopAIRequestContext.class), eq("quiet"), eq("food"), eq(2), anyString()))
                .thenReturn(PromptTemplateRender.builder()
                        .content("recommend prompt")
                        .version(PromptTemplateRegistry.RECOMMEND_VERSION)
                        .variant("stable")
                        .build());
        when(modelGateway.generateStructuredRecommendation(eq("recommend-memory"), eq("recommend prompt"), eq("quiet"),
                eq("food"), eq(candidates), any())).thenReturn(empty);
        when(modelGateway.repairStructuredRecommendation(eq("recommend-memory"), eq("recommend prompt"), eq("quiet"),
                eq("food"), eq(candidates), anyString())).thenReturn(empty);
        when(qualityGuard.validateRecommend(eq(empty), eq(Set.of(1L, 2L)), any(), eq("recommend")))
                .thenReturn(QualityCheck.builder().decision(QualityDecision.FALLBACK).reason("empty").build());
        when(fallbackPolicy.fallbackRecommend("quiet", "food", candidates, 2, "recommend", FallbackReason.QUALITY_REJECTED))
                .thenReturn(fallback);

        ShopAIResponse response = workflow.execute(context, RecommendWorkflowRequest.builder()
                .userPreference("quiet")
                .category("food")
                .limit(2)
                .build());

        assertThat(response.getRecommend().getMessage()).isEqualTo("fallback");
        assertThat(response.getDegraded()).isTrue();
        assertThat(response.getFallbackReason()).isEqualTo(FallbackReason.QUALITY_REJECTED.name());
        verify(qualityGuard, times(2)).validateRecommend(eq(empty), eq(Set.of(1L, 2L)), any(), eq("recommend"));
    }

    @Test
    void shouldFallbackWhenRecommendShopIdOutsideCandidatesAfterRepair() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        List<ShopView> candidates = List.of(shop(1L), shop(2L));
        ShopRecommendResult invalid = ShopRecommendResult.builder()
                .userPreference("quiet")
                .category("food")
                .message("invalid")
                .items(List.of(item(1, 99L)))
                .build();
        ShopRecommendResult fallback = ShopRecommendResult.builder()
                .userPreference("quiet")
                .category("food")
                .message("fallback")
                .items(List.of(item(1, 1L)))
                .build();

        when(memoryService.shopRecommendKey("u1")).thenReturn("recommend-memory");
        when(shopDataPort.findRecommendCandidates("food", 20)).thenReturn(candidates);
        when(evidenceRetriever.retrieve(anyLong(), eq("quiet"), eq("food"), eq(2))).thenReturn(Collections.emptyList());
        when(promptTemplateRegistry.renderRecommend(any(ShopAIRequestContext.class), eq("quiet"), eq("food"), eq(2), anyString()))
                .thenReturn(PromptTemplateRender.builder()
                        .content("recommend prompt")
                        .version(PromptTemplateRegistry.RECOMMEND_VERSION)
                        .variant("stable")
                        .build());
        when(modelGateway.generateStructuredRecommendation(eq("recommend-memory"), eq("recommend prompt"), eq("quiet"),
                eq("food"), eq(candidates), any())).thenReturn(invalid);
        when(modelGateway.repairStructuredRecommendation(eq("recommend-memory"), eq("recommend prompt"), eq("quiet"),
                eq("food"), eq(candidates), anyString())).thenReturn(invalid);
        when(qualityGuard.validateRecommend(eq(invalid), eq(Set.of(1L, 2L)), any(), eq("recommend")))
                .thenReturn(QualityCheck.builder().decision(QualityDecision.FALLBACK).reason("invalid shop").build());
        when(fallbackPolicy.fallbackRecommend("quiet", "food", candidates, 2, "recommend", FallbackReason.QUALITY_REJECTED))
                .thenReturn(fallback);

        ShopAIResponse response = workflow.execute(context, RecommendWorkflowRequest.builder()
                .userPreference("quiet")
                .category("food")
                .limit(2)
                .build());

        assertThat(response.getRecommend().getItems()).extracting("shopId").containsExactly(1L);
        assertThat(response.getRecommend().getItems()).doesNotContain(item(1, 99L));
        assertThat(response.getDegraded()).isTrue();
    }

    private ShopView shop(Long id) {
        return ShopView.builder()
                .id(id)
                .name("shop " + id)
                .area("area")
                .avgPrice(80L)
                .sold(100)
                .comments(30)
                .score(45)
                .build();
    }

    private ShopRecommendationItem item(Integer rank, Long shopId) {
        return ShopRecommendationItem.builder()
                .rank(rank)
                .shopId(shopId)
                .shopName("shop " + shopId)
                .reason("reason")
                .evidenceIds(List.of("shop_profile:" + shopId))
                .confidence(0.7)
                .build();
    }
}
