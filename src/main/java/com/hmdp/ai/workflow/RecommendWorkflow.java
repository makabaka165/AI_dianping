package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.RecommendWorkflowRequest;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.AiMetricsService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

@Component
public class RecommendWorkflow implements ShopAIWorkflow<RecommendWorkflowRequest, ShopAIResponse> {

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private PromptTemplateRegistry promptTemplateRegistry;

    @Resource
    private MemoryService memoryService;

    @Resource
    private ModelGateway modelGateway;

    @Resource
    private QualityGuard qualityGuard;

    @Resource
    private FallbackPolicy fallbackPolicy;

    @Resource
    private AiMetricsService aiMetricsService;

    @Override
    public ShopAIIntent intent() {
        return ShopAIIntent.RECOMMEND;
    }

    @Override
    public ShopAIResponse execute(ShopAIRequestContext context, RecommendWorkflowRequest request) {
        long start = System.currentTimeMillis();
        if (isBlank(request.getUserPreference())) {
            throw new IllegalArgumentException("用户偏好不能为空");
        }
        int safeLimit = normalizeLimit(request.getLimit(), 5);
        String memoryId = memoryService.shopRecommendKey(context.getUserId());
        context.setMemoryId(memoryId);
        List<Shop> candidates = shopMapper.selectRecommendCandidates(request.getCategory(), safeLimit);
        if (candidates == null || candidates.isEmpty()) {
            String response = "当前候选店铺数据不足，无法基于该偏好给出可靠推荐。";
            return ShopAIResponse.builder()
                    .recommendations(response)
                    .response(response)
                    .sessionId(context.getSessionId())
                    .memoryId(memoryId)
                    .traceId(context.getTraceId())
                    .evidence(Collections.emptyList())
                    .confidence(0.2)
                    .degraded(false)
                    .cacheHit(false)
                    .usedTools(Collections.emptyList())
                    .build();
        }

        String prompt = promptTemplateRegistry.recommendPrompt(
                request.getUserPreference(),
                request.getCategory(),
                safeLimit,
                candidateBlock(candidates));
        boolean degraded = false;
        String recommendations;
        if (fallbackPolicy.shouldUseFallback("analyzeShopData")) {
            recommendations = fallbackPolicy.fallbackText(memoryId, prompt, "recommend");
            degraded = true;
        } else {
            try {
                recommendations = modelGateway.generateRecommendation(memoryId, prompt);
                QualityCheck quality = qualityGuard.validateText(recommendations, "recommend");
                if (!quality.pass()) {
                    recommendations = fallbackPolicy.fallbackText(memoryId, prompt, "recommend");
                    degraded = true;
                } else {
                    recommendations = qualityGuard.postProcess(recommendations);
                }
            } catch (Exception e) {
                fallbackPolicy.recordFailure("analyzeShopData");
                recommendations = fallbackPolicy.fallbackText(memoryId, prompt, "recommend");
                degraded = true;
            }
        }
        aiMetricsService.recordDuration("recommend", System.currentTimeMillis() - start, degraded);
        return ShopAIResponse.builder()
                .recommendations(recommendations)
                .response(recommendations)
                .sessionId(context.getSessionId())
                .memoryId(memoryId)
                .traceId(context.getTraceId())
                .evidence(Collections.emptyList())
                .confidence(degraded ? 0.35 : 0.7)
                .degraded(degraded)
                .cacheHit(false)
                .usedTools(Collections.emptyList())
                .build();
    }

    private String candidateBlock(List<Shop> candidates) {
        StringBuilder builder = new StringBuilder("候选店铺：\n");
        for (Shop shop : candidates) {
            builder.append("- 店铺ID=").append(shop.getId())
                    .append(", 名称=").append(shop.getName())
                    .append(", 商圈=").append(shop.getArea())
                    .append(", 均价=").append(shop.getAvgPrice())
                    .append(", 销量=").append(shop.getSold())
                    .append(", 评论数=").append(shop.getComments())
                    .append(", 评分=").append(shop.getScore())
                    .append("\n");
        }
        return builder.toString();
    }

    private int normalizeLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(10, limit);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
