package com.hmdp.ai.workflow;

import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRender;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.QualitySummaryWorkflowRequest;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.entity.Blog;
import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.AiMetricsService;
import com.hmdp.utils.LocalCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class QualitySummaryWorkflowTest {

    @Mock
    private BlogMapper blogMapper;

    @Mock
    private ShopMapper shopMapper;

    @Mock
    private SummaryWorkflow summaryWorkflow;

    @Mock
    private LocalCacheManager localCacheManager;

    @Mock
    private AiMetricsService aiMetricsService;

    @Mock
    private MemoryService memoryService;

    @Mock
    private ModelGateway modelGateway;

    @Mock
    private PromptTemplateRegistry promptTemplateRegistry;

    private QualitySummaryWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new QualitySummaryWorkflow();
        ReflectionTestUtils.setField(workflow, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(workflow, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(workflow, "summaryWorkflow", summaryWorkflow);
        ReflectionTestUtils.setField(workflow, "localCacheManager", localCacheManager);
        ReflectionTestUtils.setField(workflow, "aiMetricsService", aiMetricsService);
        ReflectionTestUtils.setField(workflow, "memoryService", memoryService);
        ReflectionTestUtils.setField(workflow, "modelGateway", modelGateway);
        ReflectionTestUtils.setField(workflow, "promptTemplateRegistry", promptTemplateRegistry);
        lenient().when(modelGateway.modelName()).thenReturn("qwen-plus");
        lenient().when(promptTemplateRegistry.renderQualitySummary(any(), any(), any()))
                .thenReturn(PromptTemplateRender.builder()
                        .content("quality summary prompt")
                        .version(PromptTemplateRegistry.QUALITY_SUMMARY_VERSION)
                        .variant("stable")
                        .build());
    }

    @Test
    void shouldFallbackToSummaryWorkflowWhenQualityBlogsEmpty() {
        ShopAIRequestContext context = ShopAIRequestContext.builder().userId("u1").build();
        when(blogMapper.selectQualityBlogsByShopId(1L, 5, 10)).thenReturn(Collections.emptyList());
        ShopSummaryResult expected = ShopSummaryResult.builder().shopId(1L).coreSummary("summary").build();
        when(summaryWorkflow.execute(eq(context), any(SummaryWorkflowRequest.class))).thenReturn(expected);

        ShopSummaryResult result = workflow.execute(context, QualitySummaryWorkflowRequest.builder()
                .shopId(1L)
                .minLiked(5)
                .limit(10)
                .writeMemory(true)
                .build());

        assertThat(result).isSameAs(expected);
        verify(summaryWorkflow).execute(eq(context), any(SummaryWorkflowRequest.class));
        verify(localCacheManager, never()).get(any(), eq(ShopSummaryResult.class), eq(LocalCacheManager.CacheType.AI_RESULT));
    }

    @Test
    void shouldReturnRequestScopedCopyOnVersionedCacheHit() {
        Blog blog = blog(10L);
        when(blogMapper.selectQualityBlogsByShopId(1L, 5, 10)).thenReturn(List.of(blog));
        ShopSummaryResult cached = ShopSummaryResult.builder()
                .shopId(1L)
                .coreSummary("cached")
                .traceId("stale-trace")
                .memoryId("stale-memory")
                .build();
        when(localCacheManager.get(
                qualityCacheKey(blog),
                ShopSummaryResult.class,
                LocalCacheManager.CacheType.AI_RESULT)).thenReturn(cached);

        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .traceId("trace-new")
                .memoryId("memory-new")
                .build();

        ShopSummaryResult result = workflow.execute(context, QualitySummaryWorkflowRequest.builder()
                .shopId(1L)
                .minLiked(5)
                .limit(10)
                .build());

        assertThat(result).isNotSameAs(cached);
        assertThat(result.getCacheHit()).isTrue();
        assertThat(result.getTraceId()).isEqualTo("trace-new");
        assertThat(result.getMemoryId()).isEqualTo("memory-new");
        assertThat(result.getPromptVersion()).isEqualTo(PromptTemplateRegistry.QUALITY_SUMMARY_VERSION);
        assertThat(cached.getTraceId()).isEqualTo("stale-trace");
        assertThat(cached.getMemoryId()).isEqualTo("stale-memory");
    }

    @Test
    void shouldWriteSummaryMemoryOnCacheHitWhenRequested() {
        Blog blog = blog(10L);
        when(blogMapper.selectQualityBlogsByShopId(1L, 5, 10)).thenReturn(List.of(blog));
        ShopSummaryResult cached = ShopSummaryResult.builder()
                .shopId(1L)
                .coreSummary("cached")
                .confidence(0.8)
                .build();
        when(localCacheManager.get(
                qualityCacheKey(blog),
                ShopSummaryResult.class,
                LocalCacheManager.CacheType.AI_RESULT)).thenReturn(cached);
        when(memoryService.shopSummaryKey(1L, "u1")).thenReturn("summary-memory");

        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .traceId("trace-new")
                .memoryId("memory-new")
                .build();

        ShopSummaryResult result = workflow.execute(context, QualitySummaryWorkflowRequest.builder()
                .shopId(1L)
                .minLiked(5)
                .limit(10)
                .writeMemory(true)
                .build());

        assertThat(result.getCacheHit()).isTrue();
        assertThat(result.getMemoryId()).isEqualTo("memory-new");
        verify(memoryService).writeSummaryMemory(eq("summary-memory"), same(result), any());
        verify(memoryService, never()).writeSummaryMemory(eq("memory-new"), any(), any());
    }

    @Test
    void shouldNotWriteDegradedSummaryMemoryOnCacheHit() {
        Blog blog = blog(10L);
        when(blogMapper.selectQualityBlogsByShopId(1L, 5, 10)).thenReturn(List.of(blog));
        ShopSummaryResult cached = ShopSummaryResult.builder()
                .shopId(1L)
                .coreSummary("fallback")
                .confidence(0.8)
                .degraded(true)
                .build();
        when(localCacheManager.get(
                qualityCacheKey(blog),
                ShopSummaryResult.class,
                LocalCacheManager.CacheType.AI_RESULT)).thenReturn(cached);

        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .traceId("trace-new")
                .memoryId("memory-new")
                .build();

        ShopSummaryResult result = workflow.execute(context, QualitySummaryWorkflowRequest.builder()
                .shopId(1L)
                .minLiked(5)
                .limit(10)
                .writeMemory(true)
                .build());

        assertThat(result.getDegraded()).isTrue();
        verify(memoryService, never()).writeSummaryMemory(any(), any(), any());
    }

    private Blog blog(Long id) {
        return new Blog()
                .setId(id)
                .setShopId(1L)
                .setContent("good service and stable experience")
                .setLiked(20)
                .setCreateTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
    }

    private String qualityCacheKey(Blog blog) {
        return LocalCacheManager.CacheKeys.shopQualitySummaryKey(1L, 5, 10)
                + ":ctx:1:" + blog.getCreateTime()
                + ":prompt:" + PromptTemplateRegistry.QUALITY_SUMMARY_VERSION
                + ":model:qwen-plus";
    }
}
