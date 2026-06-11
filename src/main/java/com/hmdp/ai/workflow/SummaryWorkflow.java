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
            throw new IllegalArgumentException("店铺ID必须是正数");
        }

        String localCacheKey = LocalCacheManager.CacheKeys.shopSummaryKey(shopId);
        ShopSummaryResult cachedResult = localCacheManager.get(
                localCacheKey, ShopSummaryResult.class, LocalCacheManager.CacheType.AI_RESULT);
        if (cachedResult != null) {
            aiMetricsService.increment("ai.cache.hit", "summary", false);
            return cachedResult;
        }

        ShopAnalysisContext context = shopContextAssembler.buildForShop(shopId, "店铺总结");
        if (context.getTotalReviews() == null || context.getTotalReviews() == 0) {
            return createEmptyResult(shopId);
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
                .build();

        localCacheManager.put(localCacheKey, result, LocalCacheManager.CacheType.AI_RESULT);
        if (request.isWriteMemory() && requestContext.getUserId() != null) {
            memoryService.writeSummaryMemory(
                    memoryService.shopSummaryKey(shopId, requestContext.getUserId()),
                    result,
                    context);
        }
        aiMetricsService.recordDuration("summary", System.currentTimeMillis() - start, Boolean.TRUE.equals(analysis.getDegraded()));
        return result;
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
        if (cached != null) {
            aiMetricsService.increment("ai.cache.hit", "summary", false);
            return cached;
        }
        if (fallbackPolicy.shouldUseFallback("generateStructuredAnalysis")) {
            ShopAIAnalysisResult fallback = fallbackPolicy.fallbackAnalysis(shopId, "summary", true);
            aiResultCacheService.put(cacheKey, fallback);
            return fallback;
        }
        try {
            String prompt = promptTemplateRegistry.summaryPrompt(
                    context,
                    shopContextAssembler.toPromptBlock(context));
            ShopAIAnalysisResult result = modelGateway.generateStructuredSummary(prompt, context);
            QualityCheck quality = qualityGuard.validateAnalysis(result, context.safeEvidence(), "summary");
            if (!quality.pass()) {
                ShopAIAnalysisResult fallback = fallbackPolicy.fallbackAnalysis(shopId, "summary", true);
                aiResultCacheService.put(cacheKey, fallback);
                return fallback;
            }
            result.setSummary(qualityGuard.postProcess(result.getSummary()));
            result.setDegraded(false);
            aiResultCacheService.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            log.error("结构化店铺总结生成失败, shopId={}", shopId, e);
            fallbackPolicy.recordFailure("generateStructuredAnalysis");
            ShopAIAnalysisResult fallback = fallbackPolicy.fallbackAnalysis(shopId, "summary", true);
            aiResultCacheService.put(cacheKey, fallback);
            return fallback;
        }
    }

    private ShopSummaryResult createEmptyResult(Long shopId) {
        return ShopSummaryResult.builder()
                .shopId(shopId)
                .coreSummary("暂无评价数据")
                .totalBlogs(0)
                .keyPoints(Collections.emptyList())
                .summaryTime(LocalDateTime.now())
                .build();
    }
}
