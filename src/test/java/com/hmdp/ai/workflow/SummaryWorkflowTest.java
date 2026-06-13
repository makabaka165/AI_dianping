package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.GovernedGeneration;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRender;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.AiResultCacheService;
import com.hmdp.service.ShopContextAssembler;
import com.hmdp.utils.LocalCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SummaryWorkflowTest {

    @Mock
    private LocalCacheManager localCacheManager;

    @Mock
    private ShopContextAssembler shopContextAssembler;

    @Mock
    private AiMetricsService aiMetricsService;

    @Mock
    private MemoryService memoryService;

    @Mock
    private ModelGateway modelGateway;

    @Mock
    private PromptTemplateRegistry promptTemplateRegistry;

    @Mock
    private QualityGuard qualityGuard;

    @Mock
    private FallbackPolicy fallbackPolicy;

    @Mock
    private AiResultCacheService aiResultCacheService;

    private SummaryWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new SummaryWorkflow();
        ReflectionTestUtils.setField(workflow, "localCacheManager", localCacheManager);
        ReflectionTestUtils.setField(workflow, "shopContextAssembler", shopContextAssembler);
        ReflectionTestUtils.setField(workflow, "aiMetricsService", aiMetricsService);
        ReflectionTestUtils.setField(workflow, "memoryService", memoryService);
        ReflectionTestUtils.setField(workflow, "modelGateway", modelGateway);
        ReflectionTestUtils.setField(workflow, "promptTemplateRegistry", promptTemplateRegistry);
        ReflectionTestUtils.setField(workflow, "qualityGuard", qualityGuard);
        ReflectionTestUtils.setField(workflow, "fallbackPolicy", fallbackPolicy);
        ReflectionTestUtils.setField(workflow, "aiResultCacheService", aiResultCacheService);
        ReflectionTestUtils.setField(workflow, "governedGeneration", new GovernedGeneration());
        lenient().when(modelGateway.modelName()).thenReturn("qwen-plus");
        lenient().when(promptTemplateRegistry.renderSummary(any(), any(), any()))
                .thenReturn(PromptTemplateRender.builder()
                        .content("summary prompt")
                        .version(PromptTemplateRegistry.SUMMARY_VERSION)
                        .variant("stable")
                        .build());
    }

    @Test
    void shouldReturnRequestScopedCopyOnCacheHit() {
        ShopAnalysisContext analysisContext = context();
        when(shopContextAssembler.buildForShop(1L, "shop summary")).thenReturn(analysisContext);
        ShopSummaryResult cached = ShopSummaryResult.builder()
                .shopId(1L)
                .coreSummary("cached")
                .traceId("stale-trace")
                .memoryId("stale-memory")
                .build();
        when(localCacheManager.get(
                summaryCacheKey(analysisContext),
                ShopSummaryResult.class,
                LocalCacheManager.CacheType.AI_RESULT)).thenReturn(cached);

        ShopAIRequestContext requestContext = ShopAIRequestContext.builder()
                .userId("u1")
                .traceId("trace-new")
                .memoryId("memory-new")
                .build();

        ShopSummaryResult result = workflow.execute(requestContext, SummaryWorkflowRequest.builder()
                .shopId(1L)
                .build());

        assertThat(result).isNotSameAs(cached);
        assertThat(result.getCacheHit()).isTrue();
        assertThat(result.getTraceId()).isEqualTo("trace-new");
        assertThat(result.getMemoryId()).isEqualTo("memory-new");
        assertThat(result.getPromptVersion()).isEqualTo(PromptTemplateRegistry.SUMMARY_VERSION);
        assertThat(cached.getTraceId()).isEqualTo("stale-trace");
        assertThat(cached.getMemoryId()).isEqualTo("stale-memory");
    }

    @Test
    void shouldWriteSummaryMemoryOnCacheHitWhenRequested() {
        ShopAnalysisContext analysisContext = context();
        when(shopContextAssembler.buildForShop(1L, "shop summary")).thenReturn(analysisContext);
        ShopSummaryResult cached = ShopSummaryResult.builder()
                .shopId(1L)
                .coreSummary("cached")
                .confidence(0.8)
                .build();
        when(localCacheManager.get(
                summaryCacheKey(analysisContext),
                ShopSummaryResult.class,
                LocalCacheManager.CacheType.AI_RESULT)).thenReturn(cached);
        when(memoryService.shopSummaryKey(1L, "u1")).thenReturn("summary-memory");

        ShopAIRequestContext requestContext = ShopAIRequestContext.builder()
                .userId("u1")
                .traceId("trace-new")
                .memoryId("memory-new")
                .build();

        ShopSummaryResult result = workflow.execute(requestContext, SummaryWorkflowRequest.builder()
                .shopId(1L)
                .writeMemory(true)
                .build());

        assertThat(result.getCacheHit()).isTrue();
        assertThat(result.getMemoryId()).isEqualTo("memory-new");
        verify(memoryService).writeSummaryMemory(eq("summary-memory"), same(result), any());
        verify(memoryService, never()).writeSummaryMemory(eq("memory-new"), any(), any());
    }

    @Test
    void shouldNotWriteLowConfidenceEmptySummaryMemory() {
        ShopAnalysisContext analysisContext = emptyContext();
        when(shopContextAssembler.buildForShop(1L, "shop summary")).thenReturn(analysisContext);

        ShopAIRequestContext requestContext = ShopAIRequestContext.builder()
                .userId("u1")
                .traceId("trace-new")
                .memoryId("memory-new")
                .build();

        ShopSummaryResult result = workflow.execute(requestContext, SummaryWorkflowRequest.builder()
                .shopId(1L)
                .writeMemory(true)
                .build());

        assertThat(result.getConfidence()).isLessThan(0.4);
        verify(memoryService, never()).writeSummaryMemory(any(), any(), any());
    }

    @Test
    void shouldNotWriteDegradedSummaryMemoryOnCacheHit() {
        ShopAnalysisContext analysisContext = context();
        when(shopContextAssembler.buildForShop(1L, "shop summary")).thenReturn(analysisContext);
        ShopSummaryResult cached = ShopSummaryResult.builder()
                .shopId(1L)
                .coreSummary("fallback")
                .confidence(0.8)
                .degraded(true)
                .build();
        when(localCacheManager.get(
                summaryCacheKey(analysisContext),
                ShopSummaryResult.class,
                LocalCacheManager.CacheType.AI_RESULT)).thenReturn(cached);

        ShopAIRequestContext requestContext = ShopAIRequestContext.builder()
                .userId("u1")
                .traceId("trace-new")
                .memoryId("memory-new")
                .build();

        ShopSummaryResult result = workflow.execute(requestContext, SummaryWorkflowRequest.builder()
                .shopId(1L)
                .writeMemory(true)
                .build());

        assertThat(result.getDegraded()).isTrue();
        verify(memoryService, never()).writeSummaryMemory(any(), any(), any());
    }

    private ShopAnalysisContext context() {
        return ShopAnalysisContext.builder()
                .shopId(1L)
                .shopName("shop 1")
                .totalReviews(3)
                .latestReviewTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5))
                .contextVersion("3:2026-01-02T03:04:05")
                .evidence(Collections.emptyList())
                .build();
    }

    private ShopAnalysisContext emptyContext() {
        return ShopAnalysisContext.builder()
                .shopId(1L)
                .shopName("shop 1")
                .totalReviews(0)
                .latestReviewTime(null)
                .contextVersion("0:none")
                .evidence(Collections.emptyList())
                .build();
    }

    private String summaryCacheKey(ShopAnalysisContext context) {
        return LocalCacheManager.CacheKeys.shopSummaryKey(1L)
                + ":ctx:" + context.getContextVersion()
                + ":prompt:" + PromptTemplateRegistry.SUMMARY_VERSION
                + ":model:qwen-plus";
    }
}
