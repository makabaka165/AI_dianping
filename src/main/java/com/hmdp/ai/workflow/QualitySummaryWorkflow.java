package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.QualitySummaryWorkflowRequest;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.ReviewEvidence;
import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.entity.Blog;
import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.AiResultCacheService;
import com.hmdp.utils.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class QualitySummaryWorkflow implements ShopAIWorkflow<QualitySummaryWorkflowRequest, ShopSummaryResult> {

    private static final double MIN_MEMORY_CONFIDENCE = 0.4;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private SummaryWorkflow summaryWorkflow;

    @Resource
    private LocalCacheManager localCacheManager;

    @Resource
    private PromptTemplateRegistry promptTemplateRegistry;

    @Resource
    private ModelGateway modelGateway;

    @Resource
    private QualityGuard qualityGuard;

    @Resource
    private FallbackPolicy fallbackPolicy;

    @Resource
    private AiResultCacheService aiResultCacheService;

    @Resource
    private AiMetricsService aiMetricsService;

    @Resource
    private MemoryService memoryService;

    @Override
    public ShopAIIntent intent() {
        return ShopAIIntent.SUMMARY;
    }

    @Override
    public ShopSummaryResult execute(ShopAIRequestContext requestContext, QualitySummaryWorkflowRequest request) {
        long start = System.currentTimeMillis();
        Long shopId = request.getShopId();
        if (shopId == null || shopId <= 0) {
            throw new IllegalArgumentException("店铺ID必须是正数");
        }
        int minLiked = request.getMinLiked() == null ? 5 : Math.max(0, request.getMinLiked());
        int limit = normalizeLimit(request.getLimit(), 10);
        List<Blog> blogs = blogMapper.selectQualityBlogsByShopId(shopId, minLiked, limit);
        if (blogs == null || blogs.isEmpty()) {
            return summaryWorkflow.execute(requestContext, SummaryWorkflowRequest.builder()
                    .shopId(shopId)
                    .writeMemory(request.isWriteMemory())
                    .build());
        }

        ShopAnalysisContext context = buildContext(shopId, blogs);
        String localCacheKey = localQualitySummaryCacheKey(shopId, minLiked, limit, context);
        ShopSummaryResult cachedResult = localCacheManager.get(
                localCacheKey, ShopSummaryResult.class, LocalCacheManager.CacheType.AI_RESULT);
        if (cachedResult != null) {
            aiMetricsService.increment("ai.cache.hit", "quality_summary", false);
            ShopSummaryResult response = attachMetadata(
                    cachedResult, requestContext, true, PromptTemplateRegistry.QUALITY_SUMMARY_VERSION);
            writeMemoryIfNeeded(request, requestContext, shopId, response, context);
            aiMetricsService.recordDuration("quality_summary", System.currentTimeMillis() - start, false);
            return response;
        }

        ShopAIAnalysisResult analysis = generateAnalysis(shopId, minLiked, limit, context);
        ShopSummaryResult result = ShopSummaryResult.builder()
                .shopId(shopId)
                .shopName(context.getShopName())
                .coreSummary(analysis.getSummary())
                .totalBlogs(blogs.size())
                .keyPoints(analysis.safeKeywords())
                .overallSentiment(analysis.getSentiment())
                .summaryTime(LocalDateTime.now())
                .evidence(context.safeEvidence())
                .confidence(analysis.getConfidence())
                .degraded(Boolean.TRUE.equals(analysis.getDegraded()))
                .cacheHit(false)
                .fallbackReason(fallbackReason(analysis))
                .build();

        if (!Boolean.TRUE.equals(result.getDegraded())) {
            localCacheManager.put(localCacheKey, result.withoutRequestMetadata(), LocalCacheManager.CacheType.AI_RESULT);
        }
        ShopSummaryResult response = attachMetadata(
                result, requestContext, false, PromptTemplateRegistry.QUALITY_SUMMARY_VERSION);
        writeMemoryIfNeeded(request, requestContext, shopId, response, context);
        aiMetricsService.recordDuration("quality_summary", System.currentTimeMillis() - start,
                Boolean.TRUE.equals(analysis.getDegraded()));
        return response;
    }

    private ShopAIAnalysisResult generateAnalysis(Long shopId, int minLiked, int limit, ShopAnalysisContext context) {
        String cacheKey = aiResultCacheService.buildShopAnalysisKey(
                shopId,
                context.getContextVersion(),
                PromptTemplateRegistry.QUALITY_SUMMARY_VERSION,
                ModelGateway.MODEL_NAME,
                "quality_summary",
                "minLiked=" + minLiked + ":limit=" + limit);
        ShopAIAnalysisResult cached = aiResultCacheService.get(cacheKey, ShopAIAnalysisResult.class);
        if (cached != null && !Boolean.TRUE.equals(cached.getDegraded())) {
            aiMetricsService.increment("ai.cache.hit", "quality_summary", false);
            return cached;
        }
        if (fallbackPolicy.shouldUseFallback("generateStructuredAnalysis")) {
            return fallbackPolicy.fallbackAnalysis(shopId, "quality_summary", true);
        }
        try {
            String prompt = promptTemplateRegistry.qualitySummaryPrompt(context, toPromptBlock(context));
            ShopAIAnalysisResult result = modelGateway.generateStructuredSummary(prompt, context);
            QualityCheck quality = qualityGuard.validateAnalysis(result, context.safeEvidence(), "quality_summary");
            if (!quality.pass()) {
                return fallbackPolicy.fallbackAnalysis(shopId, "quality_summary", true);
            }
            result.setSummary(qualityGuard.postProcess(result.getSummary()));
            result.setDegraded(false);
            aiResultCacheService.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            log.error("高质量店铺总结生成失败, shopId={}", shopId, e);
            fallbackPolicy.recordFailure("generateStructuredAnalysis");
            return fallbackPolicy.fallbackAnalysis(shopId, "quality_summary", true);
        }
    }

    private ShopAnalysisContext buildContext(Long shopId, List<Blog> blogs) {
        LocalDateTime latest = blogs.stream()
                .map(Blog::getCreateTime)
                .filter(time -> time != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        List<ReviewEvidence> evidence = blogs.stream()
                .map(blog -> ReviewEvidence.builder()
                        .blogId(blog.getId())
                        .shopId(shopId)
                        .snippet(truncate(blog.getContent(), 300))
                        .liked(blog.getLiked())
                        .createdAt(blog.getCreateTime())
                        .matchedReason("high_liked")
                        .score((blog.getLiked() == null ? 0 : blog.getLiked()) / 100.0)
                        .build())
                .collect(Collectors.toList());
        String contextVersion = blogs.size() + ":" + (latest == null ? "none" : latest.toString());
        return ShopAnalysisContext.builder()
                .shopId(shopId)
                .shopName("店铺" + shopId)
                .totalReviews(blogs.size())
                .latestReviewTime(latest)
                .contextVersion(contextVersion)
                .evidence(evidence)
                .build();
    }

    private String toPromptBlock(ShopAnalysisContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("店铺ID: ").append(context.getShopId()).append("\n");
        prompt.append("店铺名称: ").append(context.getShopName()).append("\n");
        prompt.append("高质量评价数: ").append(context.getTotalReviews()).append("\n");
        prompt.append("上下文版本: ").append(context.getContextVersion()).append("\n");
        prompt.append("高质量评价证据:\n");
        int index = 1;
        for (ReviewEvidence evidence : context.safeEvidence()) {
            prompt.append("[证据").append(index++).append(" blogId=").append(evidence.getBlogId()).append("] ")
                    .append("点赞=").append(evidence.getLiked()).append(", ")
                    .append("内容=").append(evidence.getSnippet()).append("\n");
        }
        if (context.safeEvidence().isEmpty()) {
            prompt.append("无可用高质量评价证据。\n");
        }
        return prompt.toString();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private int normalizeLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(10, limit);
    }

    private String localQualitySummaryCacheKey(Long shopId,
                                               int minLiked,
                                               int limit,
                                               ShopAnalysisContext context) {
        return LocalCacheManager.CacheKeys.shopQualitySummaryKey(shopId, minLiked, limit)
                + ":ctx:" + safe(context == null ? null : context.getContextVersion())
                + ":prompt:" + PromptTemplateRegistry.QUALITY_SUMMARY_VERSION
                + ":model:" + ModelGateway.MODEL_NAME;
    }

    private String safe(String value) {
        return value == null ? "none" : value.replaceAll("[^a-zA-Z0-9_.:-]", "_");
    }

    private ShopSummaryResult attachMetadata(ShopSummaryResult source,
                                             ShopAIRequestContext requestContext,
                                             boolean cacheHit,
                                             String promptVersion) {
        ShopSummaryResult result = source.copy();
        result.setTraceId(requestContext.getTraceId());
        result.setMemoryId(requestContext.getMemoryId());
        result.setPromptVersion(promptVersion);
        result.setModelName(ModelGateway.MODEL_NAME);
        result.setCacheHit(cacheHit);
        return result;
    }

    private void writeMemoryIfNeeded(QualitySummaryWorkflowRequest request,
                                     ShopAIRequestContext requestContext,
                                     Long shopId,
                                     ShopSummaryResult result,
                                     ShopAnalysisContext context) {
        if (!request.isWriteMemory() || requestContext.getUserId() == null) {
            return;
        }
        if (Boolean.TRUE.equals(result.getDegraded())
                || (result.getConfidence() != null && result.getConfidence() < MIN_MEMORY_CONFIDENCE)) {
            return;
        }
        String summaryMemoryId = memoryService.shopSummaryKey(shopId, requestContext.getUserId());
        if (requestContext.getMemoryId() == null || requestContext.getMemoryId().trim().isEmpty()) {
            requestContext.setMemoryId(summaryMemoryId);
            result.setMemoryId(summaryMemoryId);
        }
        memoryService.writeSummaryMemory(summaryMemoryId, result, context);
    }

    private String fallbackReason(ShopAIAnalysisResult analysis) {
        return Boolean.TRUE.equals(analysis.getDegraded()) ? "AI_MODEL_OR_QUALITY_FALLBACK" : null;
    }
}
