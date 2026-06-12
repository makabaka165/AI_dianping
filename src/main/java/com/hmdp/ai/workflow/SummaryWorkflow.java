package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.AiResultCacheService;
import com.hmdp.service.ShopContextAssembler;
import com.hmdp.utils.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

@Component
@Slf4j
public class SummaryWorkflow implements ShopAIWorkflow<SummaryWorkflowRequest, ShopSummaryResult> {

    private static final double MIN_MEMORY_CONFIDENCE = 0.4;

    @Resource
    private LocalCacheManager localCacheManager;

    @Resource
    private ShopContextAssembler shopContextAssembler;

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
    public ShopSummaryResult execute(ShopAIRequestContext requestContext, SummaryWorkflowRequest request) {
        long start = System.currentTimeMillis();
        Long shopId = request.getShopId();
        if (shopId == null || shopId <= 0) {
            throw new IllegalArgumentException("shopId must be positive");
        }

        ShopAnalysisContext localContext = shopContextAssembler.buildForShop(shopId, "shop summary");
        String localCacheKey = localSummaryCacheKey(shopId, localContext);
        ShopSummaryResult cachedResult = localCacheManager.get(
                localCacheKey, ShopSummaryResult.class, LocalCacheManager.CacheType.AI_RESULT);
        if (cachedResult != null) {
            aiMetricsService.increment("ai.cache.hit", "summary", false);
            ShopSummaryResult response = attachMetadata(
                    cachedResult, requestContext, true, PromptTemplateRegistry.SUMMARY_VERSION);
            writeMemoryIfNeeded(request, requestContext, shopId, response, localContext);
            aiMetricsService.recordDuration("summary", System.currentTimeMillis() - start, false);
            return response;
        }

        ShopAnalysisContext context = localContext;
        if (context.getTotalReviews() == null || context.getTotalReviews() == 0) {
            ShopSummaryResult response = attachMetadata(
                    createEmptyResult(shopId), requestContext, false, PromptTemplateRegistry.SUMMARY_VERSION);
            writeMemoryIfNeeded(request, requestContext, shopId, response, context);
            aiMetricsService.recordDuration("summary", System.currentTimeMillis() - start, false);
            return response;
        }

        ShopAIAnalysisResult analysis = generateAnalysis(shopId, context);
        ShopSummaryResult result = ShopSummaryResult.builder()
                .shopId(shopId)
                .shopName(context.getShopName())
                .coreSummary(analysis.getSummary())
                .totalBlogs(context.getTotalReviews())
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
                result, requestContext, false, PromptTemplateRegistry.SUMMARY_VERSION);
        writeMemoryIfNeeded(request, requestContext, shopId, response, context);
        aiMetricsService.recordDuration("summary", System.currentTimeMillis() - start, Boolean.TRUE.equals(analysis.getDegraded()));
        return response;
    }

    private String localSummaryCacheKey(Long shopId, ShopAnalysisContext context) {
        return LocalCacheManager.CacheKeys.shopSummaryKey(shopId)
                + ":ctx:" + safe(context == null ? null : context.getContextVersion())
                + ":prompt:" + PromptTemplateRegistry.SUMMARY_VERSION
                + ":model:" + ModelGateway.MODEL_NAME;
    }

    private String safe(String value) {
        return value == null ? "none" : value.replaceAll("[^a-zA-Z0-9_.:-]", "_");
    }

    private ShopAIAnalysisResult generateAnalysis(Long shopId, ShopAnalysisContext context) {
        String cacheKey = aiResultCacheService.buildShopAnalysisKey(
                shopId,
                context.getContextVersion(),
                PromptTemplateRegistry.SUMMARY_VERSION,
                ModelGateway.MODEL_NAME,
                "summary",
                "default");
        ShopAIAnalysisResult cached = aiResultCacheService.get(cacheKey, ShopAIAnalysisResult.class);
        if (cached != null && !Boolean.TRUE.equals(cached.getDegraded())) {
            aiMetricsService.increment("ai.cache.hit", "summary", false);
            return cached;
        }
        if (fallbackPolicy.shouldUseFallback("generateStructuredAnalysis")) {
            return fallbackPolicy.fallbackAnalysis(shopId, "summary", true);
        }
        try {
            String prompt = promptTemplateRegistry.summaryPrompt(
                    context,
                    shopContextAssembler.toPromptBlock(context));
            ShopAIAnalysisResult result = modelGateway.generateStructuredSummary(prompt, context);
            QualityCheck quality = qualityGuard.validateAnalysis(result, context.safeEvidence(), "summary");
            if (!quality.pass()) {
                return fallbackPolicy.fallbackAnalysis(shopId, "summary", true);
            }
            result.setSummary(qualityGuard.postProcess(result.getSummary()));
            result.setDegraded(false);
            aiResultCacheService.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            log.error("结构化店铺总结生成失败, shopId={}", shopId, e);
            fallbackPolicy.recordFailure("generateStructuredAnalysis");
            return fallbackPolicy.fallbackAnalysis(shopId, "summary", true);
        }
    }

    private ShopSummaryResult createEmptyResult(Long shopId) {
        return ShopSummaryResult.builder()
                .shopId(shopId)
                .coreSummary("暂无评价数据")
                .totalBlogs(0)
                .keyPoints(Collections.emptyList())
                .summaryTime(LocalDateTime.now())
                .evidence(Collections.emptyList())
                .confidence(0.2)
                .degraded(false)
                .cacheHit(false)
                .build();
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

    private void writeMemoryIfNeeded(SummaryWorkflowRequest request,
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
