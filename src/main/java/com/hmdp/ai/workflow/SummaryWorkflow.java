package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.GovernedGeneration;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.port.ShopDataPort;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRender;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.dto.ai.ShopSummaryResult;
import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.infra.AiResultCacheService;
import com.hmdp.ai.retrieval.ShopContextAssembler;
import com.hmdp.utils.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

@Component
@Slf4j
public class SummaryWorkflow {

    private static final double MIN_MEMORY_CONFIDENCE = 0.4;
    private static final String ANALYSIS_TYPE = "summary";

    @Resource
    private LocalCacheManager localCacheManager;

    @Resource
    private ShopContextAssembler shopContextAssembler;

    @Resource
    private ShopDataPort shopDataPort;

    @Resource
    private PromptTemplateRegistry promptTemplateRegistry;

    @Resource
    private ModelGateway modelGateway;

    @Resource
    private QualityGuard qualityGuard;

    @Resource
    private FallbackPolicy fallbackPolicy;

    @Resource
    private GovernedGeneration governedGeneration;

    @Resource
    private AiResultCacheService aiResultCacheService;

    @Resource
    private AiMetricsService aiMetricsService;

    @Resource
    private MemoryService memoryService;

    public ShopSummaryResult execute(ShopAIRequestContext requestContext, SummaryWorkflowRequest request) {
        long start = System.currentTimeMillis();
        Long shopId = request.getShopId();
        if (shopId == null || shopId <= 0) {
            throw new IllegalArgumentException("shopId must be positive");
        }
        String availabilityMessage = availabilityMessage(shopId);
        if (availabilityMessage != null) {
            ShopSummaryResult response = attachMetadata(
                    createUnavailableResult(shopId, availabilityMessage),
                    requestContext,
                    false,
                    PromptTemplateRegistry.SUMMARY_VERSION);
            aiMetricsService.recordDuration("summary", System.currentTimeMillis() - start, false);
            return response;
        }

        ShopAnalysisContext localContext = shopContextAssembler.buildForShop(shopId, "shop summary");
        if (localContext.safeEvidence().isEmpty()) {
            ShopSummaryResult response = attachMetadata(
                    createInsufficientEvidenceResult(shopId, localContext),
                    requestContext,
                    false,
                    PromptTemplateRegistry.SUMMARY_VERSION);
            aiMetricsService.recordDuration("summary", System.currentTimeMillis() - start, false);
            return response;
        }
        PromptTemplateRender prompt = promptTemplateRegistry.renderSummary(
                requestContext, localContext, shopContextAssembler.toPromptBlock(localContext));
        String localCacheKey = localSummaryCacheKey(shopId, localContext, prompt.getVersion());
        ShopSummaryResult cachedResult = localCacheManager.get(
                localCacheKey, ShopSummaryResult.class, LocalCacheManager.CacheType.AI_RESULT);
        if (cachedResult != null) {
            aiMetricsService.increment("ai.cache.hit", "summary", false);
            ShopSummaryResult response = attachMetadata(
                    cachedResult, requestContext, true, prompt.getVersion());
            writeMemoryIfNeeded(request, requestContext, shopId, response, localContext);
            aiMetricsService.recordDuration("summary", System.currentTimeMillis() - start, false);
            return response;
        }

        ShopAnalysisContext context = localContext;
        if (context.getTotalReviews() == null || context.getTotalReviews() == 0) {
            ShopSummaryResult response = attachMetadata(
                    createEmptyResult(shopId), requestContext, false, prompt.getVersion());
            writeMemoryIfNeeded(request, requestContext, shopId, response, context);
            aiMetricsService.recordDuration("summary", System.currentTimeMillis() - start, false);
            return response;
        }

        ShopAIAnalysisResult analysis = generateAnalysis(shopId, context, prompt);
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
                result, requestContext, false, prompt.getVersion());
        writeMemoryIfNeeded(request, requestContext, shopId, response, context);
        aiMetricsService.recordDuration("summary", System.currentTimeMillis() - start, Boolean.TRUE.equals(analysis.getDegraded()));
        return response;
    }

    private String localSummaryCacheKey(Long shopId, ShopAnalysisContext context, String promptVersion) {
        return LocalCacheManager.CacheKeys.shopSummaryKey(shopId)
                + ":ctx:" + safe(context == null ? null : context.getContextVersion())
                + ":prompt:" + safe(promptVersion)
                + ":model:" + modelName();
    }

    private String safe(String value) {
        return value == null ? "none" : value.replaceAll("[^a-zA-Z0-9_.:-]", "_");
    }

    private ShopAIAnalysisResult generateAnalysis(Long shopId,
                                                  ShopAnalysisContext context,
                                                  PromptTemplateRender prompt) {
        String cacheKey = aiResultCacheService.buildShopAnalysisKey(
                shopId,
                context.getContextVersion(),
                prompt.getVersion(),
                modelName(),
                ANALYSIS_TYPE,
                "default");
        ShopAIAnalysisResult cached = aiResultCacheService.get(cacheKey, ShopAIAnalysisResult.class);
        if (cached != null && !Boolean.TRUE.equals(cached.getDegraded())) {
            aiMetricsService.increment("ai.cache.hit", "summary", false);
            return cached;
        }
        GovernedGeneration.GovernedResult<ShopAIAnalysisResult> generated = governedGeneration.runWithReason(
                () -> modelGateway.generateStructuredSummary(prompt.getContent(), context),
                reason -> modelGateway.repairStructuredSummary(prompt.getContent(), context, reason),
                analysis -> qualityGuard.validateAnalysis(analysis, context.safeEvidence(), ANALYSIS_TYPE),
                reason -> fallbackPolicy.fallbackAnalysis(shopId, ANALYSIS_TYPE, true, reason),
                analysis -> {
                    analysis.setSummary(qualityGuard.postProcess(analysis.getSummary()));
                    analysis.setDegraded(false);
                    return analysis;
                });
        if (generated.getFallbackReason() != null && generated.getValue() != null) {
            generated.getValue().setDegraded(true);
            generated.getValue().setFallbackReason(generated.getFallbackReason().name());
        }
        if (!generated.isDegraded()) {
            aiResultCacheService.put(cacheKey, generated.getValue());
        }
        return generated.getValue();
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

    private ShopSummaryResult createUnavailableResult(Long shopId, String message) {
        return ShopSummaryResult.builder()
                .shopId(shopId)
                .coreSummary(message)
                .totalBlogs(0)
                .keyPoints(Collections.emptyList())
                .summaryTime(LocalDateTime.now())
                .evidence(Collections.emptyList())
                .confidence(0.2)
                .degraded(false)
                .cacheHit(false)
                .build();
    }

    private ShopSummaryResult createInsufficientEvidenceResult(Long shopId, ShopAnalysisContext context) {
        return ShopSummaryResult.builder()
                .shopId(shopId)
                .shopName(context == null ? null : context.getShopName())
                .coreSummary("当前评价证据不足以判断店铺" + shopId + "的情况。")
                .totalBlogs(context == null || context.getTotalReviews() == null ? 0 : context.getTotalReviews())
                .keyPoints(Collections.emptyList())
                .summaryTime(LocalDateTime.now())
                .evidence(Collections.emptyList())
                .confidence(0.2)
                .degraded(false)
                .cacheHit(false)
                .build();
    }

    private String availabilityMessage(Long shopId) {
        if (!shopDataPort.shopExists(shopId)) {
            return "店铺不存在";
        }
        if (shopDataPort.getReviewCount(shopId) <= 0) {
            return "暂无评价数据";
        }
        return null;
    }

    private ShopSummaryResult attachMetadata(ShopSummaryResult source,
                                             ShopAIRequestContext requestContext,
                                             boolean cacheHit,
                                             String promptVersion) {
        ShopSummaryResult result = source.copy();
        result.setTraceId(requestContext.getTraceId());
        result.setMemoryId(requestContext.getMemoryId());
        result.setPromptVersion(promptVersion);
        result.setModelName(modelName());
        result.setCacheHit(cacheHit);
        if (cacheHit) {
            result.setFallbackReason(null);
        }
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
        return Boolean.TRUE.equals(analysis.getDegraded())
                ? (analysis.getFallbackReason() == null ? "MODEL_UNAVAILABLE" : analysis.getFallbackReason())
                : null;
    }

    private String modelName() {
        return modelGateway == null ? ModelGateway.DEFAULT_MODEL_NAME : modelGateway.modelName();
    }
}
