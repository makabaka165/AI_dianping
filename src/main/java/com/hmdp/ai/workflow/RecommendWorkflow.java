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
import com.hmdp.dto.ai.ReviewEvidence;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.ShopReviewEvidenceRetriever;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class RecommendWorkflow implements ShopAIWorkflow<RecommendWorkflowRequest, ShopAIResponse> {

    private static final int EVIDENCE_SNIPPET_LIMIT = 300;
    private static final int SHOP_FIELD_LIMIT = 120;

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private ShopReviewEvidenceRetriever evidenceRetriever;

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

        List<ReviewEvidence> evidence = recommendationEvidence(candidates, request.getUserPreference(), request.getCategory());
        String prompt = promptTemplateRegistry.recommendPrompt(
                request.getUserPreference(),
                request.getCategory(),
                safeLimit,
                candidateBlock(candidates) + evidenceBlock(evidence));
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
                .evidence(evidence)
                .confidence(degraded ? 0.35 : 0.7)
                .degraded(degraded)
                .cacheHit(false)
                .usedTools(Collections.emptyList())
                .build();
    }

    public StreamWorkflowPlan prepareStreamPlan(ShopAIRequestContext context, RecommendWorkflowRequest request) {
        if (isBlank(request.getUserPreference())) {
            throw new IllegalArgumentException("userPreference must not be blank");
        }
        int safeLimit = normalizeLimit(request.getLimit(), 5);
        String memoryId = memoryService.shopRecommendKey(context.getUserId());
        context.setMemoryId(memoryId);
        List<Shop> candidates = shopMapper.selectRecommendCandidates(request.getCategory(), safeLimit);
        if (candidates == null || candidates.isEmpty()) {
            return StreamWorkflowPlan.builder()
                    .analysisType("recommend")
                    .memoryId(memoryId)
                    .directText("当前候选店铺数据不足，无法基于该偏好给出可靠推荐。")
                    .evidence(Collections.emptyList())
                    .confidence(0.2)
                    .degraded(false)
                    .cacheHit(false)
                    .build();
        }

        List<ReviewEvidence> evidence = recommendationEvidence(candidates, request.getUserPreference(), request.getCategory());
        String prompt = promptTemplateRegistry.recommendPrompt(
                request.getUserPreference(),
                request.getCategory(),
                safeLimit,
                candidateBlock(candidates) + evidenceBlock(evidence));
        return StreamWorkflowPlan.builder()
                .analysisType("recommend")
                .memoryId(memoryId)
                .prompt(prompt)
                .evidence(evidence)
                .confidence(0.7)
                .degraded(false)
                .cacheHit(false)
                .build();
    }

    private List<ReviewEvidence> recommendationEvidence(List<Shop> candidates, String preference, String category) {
        List<ReviewEvidence> evidence = new ArrayList<>();
        if (candidates == null) {
            return evidence;
        }
        for (Shop shop : candidates) {
            if (shop == null || shop.getId() == null) {
                continue;
            }
            List<ReviewEvidence> reviews = evidenceRetriever.retrieve(shop.getId(), preference, category, 2);
            if (reviews == null || reviews.isEmpty()) {
                evidence.add(profileEvidence(shop));
            } else {
                evidence.addAll(reviews);
            }
            if (evidence.size() >= 10) {
                return new ArrayList<>(evidence.subList(0, 10));
            }
        }
        return evidence;
    }

    private ReviewEvidence profileEvidence(Shop shop) {
        String snippet = "shopId=" + shop.getId()
                + ", name=" + truncate(shop.getName(), SHOP_FIELD_LIMIT)
                + ", area=" + truncate(shop.getArea(), SHOP_FIELD_LIMIT)
                + ", avgPrice=" + shop.getAvgPrice()
                + ", sold=" + shop.getSold()
                + ", comments=" + shop.getComments()
                + ", score=" + shop.getScore();
        return ReviewEvidence.builder()
                .blogId(null)
                .shopId(shop.getId())
                .snippet(snippet)
                .liked(0)
                .matchedReason("recommend candidate profile")
                .score(0.45)
                .build();
    }

    private String evidenceBlock(List<ReviewEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "\n候选证据: 暂无评价证据，仅可基于候选店铺公开字段做低置信推荐。\n";
        }
        StringBuilder builder = new StringBuilder("\n候选证据:\n");
        int index = 1;
        for (ReviewEvidence item : evidence) {
            builder.append("[证据").append(index++).append(" shopId=").append(item.getShopId());
            if (item.getBlogId() != null) {
                builder.append(", blogId=").append(item.getBlogId());
            }
            builder.append("] ")
                    .append(item.getMatchedReason())
                    .append(": ")
                    .append(truncate(item.getSnippet(), EVIDENCE_SNIPPET_LIMIT))
                    .append("\n");
        }
        return builder.toString();
    }

    private String candidateBlock(List<Shop> candidates) {
        StringBuilder builder = new StringBuilder("候选店铺：\n");
        for (Shop shop : candidates) {
            builder.append("- 店铺ID=").append(shop.getId())
                    .append(", 名称=").append(truncate(shop.getName(), SHOP_FIELD_LIMIT))
                    .append(", 商圈=").append(truncate(shop.getArea(), SHOP_FIELD_LIMIT))
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

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...[truncated]";
    }
}
