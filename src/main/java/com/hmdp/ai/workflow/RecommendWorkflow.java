package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.GovernedGeneration;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.ai.port.ShopDataPort;
import com.hmdp.ai.prompt.PromptTemplateRender;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.RecommendWorkflowRequest;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.EvidenceType;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopProfileSnapshot;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopView;
import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.retrieval.ShopReviewEvidenceRetriever;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

@Component
public class RecommendWorkflow {

    private static final String ANALYSIS_TYPE = "recommend";
    private static final int EVIDENCE_SNIPPET_LIMIT = 300;
    private static final int SHOP_FIELD_LIMIT = 120;

    @Resource
    private ShopDataPort shopDataPort;

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
    private GovernedGeneration governedGeneration;

    @Resource
    private AiMetricsService aiMetricsService;

    public ShopAIResponse execute(ShopAIRequestContext context, RecommendWorkflowRequest request) {
        long start = System.currentTimeMillis();
        if (isBlank(request.getUserPreference())) {
            throw new IllegalArgumentException("用户偏好不能为空");
        }
        int requestedLimit = normalizeLimit(request.getLimit(), 5);
        int candidateLimit = candidateLimit(requestedLimit);
        String memoryId = memoryService.shopRecommendKey(context.getUserId());
        context.setMemoryId(memoryId);
        List<ShopView> candidates = shopDataPort.findRecommendCandidates(request.getCategory(), candidateLimit);
        if (candidates == null || candidates.isEmpty()) {
            ShopRecommendResult recommend = ShopRecommendResult.builder()
                    .userPreference(request.getUserPreference())
                    .category(request.getCategory())
                    .message("当前候选店铺数据不足，无法基于该偏好给出可靠推荐。")
                    .items(Collections.emptyList())
                    .build();
            return response(context, Collections.emptyList(), recommend, false, 0.2, null,
                    PromptTemplateRegistry.RECOMMEND_VERSION);
        }

        List<EvidenceItem> evidence = recommendationEvidence(candidates, request.getUserPreference(), request.getCategory());
        PromptTemplateRender prompt = promptTemplateRegistry.renderRecommend(
                context,
                request.getUserPreference(),
                request.getCategory(),
                requestedLimit,
                candidateBlock(candidates) + evidenceBlock(evidence));
        Set<Long> candidateShopIds = candidates.stream()
                .filter(shop -> shop != null && shop.getId() != null)
                .map(ShopView::getId)
                .collect(Collectors.toSet());
        GovernedGeneration.GovernedResult<ShopRecommendResult> generated = governedGeneration.runWithReason(
                () -> modelGateway.generateStructuredRecommendation(memoryId, prompt.getContent(), request.getUserPreference(),
                        request.getCategory(), candidates, evidence),
                reason -> modelGateway.repairStructuredRecommendation(memoryId, prompt.getContent(), request.getUserPreference(),
                        request.getCategory(), candidates, reason),
                recommend -> qualityGuard.validateRecommend(recommend, candidateShopIds, evidence, ANALYSIS_TYPE),
                reason -> fallbackPolicy.fallbackRecommend(request.getUserPreference(), request.getCategory(),
                        candidates, requestedLimit, ANALYSIS_TYPE, reason),
                UnaryOperator.identity());
        ShopRecommendResult recommend = limitRecommendItems(generated.getValue(), requestedLimit);
        boolean degraded = generated.isDegraded();
        String fallbackReason = generated.getFallbackReason() == null ? null : generated.getFallbackReason().name();
        aiMetricsService.recordDuration(ANALYSIS_TYPE, System.currentTimeMillis() - start, degraded);
        aiMetricsService.recordEvidenceCount(ANALYSIS_TYPE, evidence.size(), "hybrid");
        return response(context, evidence, recommend, degraded, degraded ? 0.35 : 0.7,
                fallbackReason,
                prompt.getVersion());
    }

    public StreamWorkflowPlan prepareStreamPlan(ShopAIRequestContext context, RecommendWorkflowRequest request) {
        if (isBlank(request.getUserPreference())) {
            throw new IllegalArgumentException("userPreference must not be blank");
        }
        int requestedLimit = normalizeLimit(request.getLimit(), 5);
        int candidateLimit = candidateLimit(requestedLimit);
        String memoryId = memoryService.shopRecommendKey(context.getUserId());
        context.setMemoryId(memoryId);
        List<ShopView> candidates = shopDataPort.findRecommendCandidates(request.getCategory(), candidateLimit);
        if (candidates == null || candidates.isEmpty()) {
            return StreamWorkflowPlan.builder()
                    .analysisType(ANALYSIS_TYPE)
                    .memoryId(memoryId)
                    .directText("当前候选店铺数据不足，无法基于该偏好给出可靠推荐。")
                    .evidence(Collections.emptyList())
                    .confidence(0.2)
                    .degraded(false)
                    .cacheHit(false)
                    .build();
        }

        List<EvidenceItem> evidence = recommendationEvidence(candidates, request.getUserPreference(), request.getCategory());
        PromptTemplateRender prompt = promptTemplateRegistry.renderRecommend(
                context,
                request.getUserPreference(),
                request.getCategory(),
                requestedLimit,
                candidateBlock(candidates) + evidenceBlock(evidence));
        return StreamWorkflowPlan.builder()
                .analysisType(ANALYSIS_TYPE)
                .memoryId(memoryId)
                .prompt(prompt.getContent())
                .promptVersion(prompt.getVersion())
                .evidence(evidence)
                .confidence(0.7)
                .degraded(false)
                .cacheHit(false)
                .structuredOutput(true)
                .expectedUserPreference(request.getUserPreference())
                .expectedCategory(request.getCategory())
                .requestedLimit(requestedLimit)
                .candidateShopIds(candidateIds(candidates))
                .candidateShops(candidates)
                .build();
    }

    private Set<Long> candidateIds(List<ShopView> candidates) {
        Set<Long> ids = new LinkedHashSet<>();
        if (candidates != null) {
            for (ShopView candidate : candidates) {
                if (candidate != null && candidate.getId() != null) ids.add(candidate.getId());
            }
        }
        return ids;
    }

    private List<EvidenceItem> recommendationEvidence(List<ShopView> candidates, String preference, String category) {
        List<EvidenceItem> evidence = new ArrayList<>();
        if (candidates == null) {
            return evidence;
        }
        for (ShopView shop : candidates) {
            if (shop == null || shop.getId() == null) {
                continue;
            }
            evidence.add(profileEvidence(shop));
            List<EvidenceItem> reviews = evidenceRetriever.retrieve(shop.getId(), preference, category, 2);
            if (reviews != null) {
                evidence.addAll(reviews);
            }
            if (evidence.size() >= 10) {
                return new ArrayList<>(evidence.subList(0, 10));
            }
        }
        return evidence;
    }

    private EvidenceItem profileEvidence(ShopView shop) {
        ShopProfileSnapshot profile = ShopProfileSnapshot.from(shop);
        String snippet = "shopId=" + shop.getId()
                + ", name=" + truncate(shop.getName(), SHOP_FIELD_LIMIT)
                + ", area=" + truncate(shop.getArea(), SHOP_FIELD_LIMIT)
                + ", address=" + truncate(shop.getAddress(), SHOP_FIELD_LIMIT)
                + ", avgPrice=" + shop.getAvgPrice()
                + ", sold=" + shop.getSold()
                + ", comments=" + shop.getComments()
                + ", score=" + shop.getScore()
                + ", openHours=" + truncate(shop.getOpenHours(), SHOP_FIELD_LIMIT);
        return EvidenceItem.builder()
                .id(EvidenceItem.shopProfileId(shop.getId()))
                .type(EvidenceType.SHOP_PROFILE)
                .shopId(shop.getId())
                .sourceId(shop.getId())
                .title("店铺公开资料#" + shop.getId())
                .snippet(snippet)
                .liked(0)
                .matchedReason("recommend candidate profile")
                .score(0.45)
                .shopProfile(profile)
                .build();
    }

    private String evidenceBlock(List<EvidenceItem> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "\n候选证据：暂无评价证据，仅可基于候选店铺公开字段做低置信推荐。\n";
        }
        StringBuilder builder = new StringBuilder("\n候选证据:\n");
        int index = 1;
        for (EvidenceItem item : evidence) {
            builder.append("[证据").append(index++).append(" evidenceId=").append(item.getId())
                    .append(", type=").append(item.getType())
                    .append(", shopId=").append(item.getShopId())
                    .append("] ")
                    .append(item.getMatchedReason())
                    .append(": ")
                    .append(truncate(item.getSnippet(), EVIDENCE_SNIPPET_LIMIT))
                    .append("\n");
        }
        return builder.toString();
    }

    private String candidateBlock(List<ShopView> candidates) {
        StringBuilder builder = new StringBuilder("候选店铺:\n");
        for (ShopView shop : candidates) {
            builder.append("- 店铺ID=").append(shop.getId())
                    .append(", 名称=").append(truncate(shop.getName(), SHOP_FIELD_LIMIT))
                    .append(", 商圈=").append(truncate(shop.getArea(), SHOP_FIELD_LIMIT))
                    .append(", 地址=").append(truncate(shop.getAddress(), SHOP_FIELD_LIMIT))
                    .append(", 均价=").append(shop.getAvgPrice())
                    .append(", 销量=").append(shop.getSold())
                    .append(", 评论数=").append(shop.getComments())
                    .append(", 评分=").append(shop.getScore())
                    .append(", 公开资料证据ID=").append(EvidenceItem.shopProfileId(shop.getId()))
                    .append("\n");
        }
        return builder.toString();
    }

    private ShopAIResponse response(ShopAIRequestContext context,
                                    List<EvidenceItem> evidence,
                                    ShopRecommendResult recommend,
                                    boolean degraded,
                                    double confidence,
                                    String fallbackReason,
                                    String promptVersion) {
        return ShopAIResponse.builder()
                .recommend(recommend)
                .sessionId(context.getSessionId())
                .memoryId(context.getMemoryId())
                .traceId(context.getTraceId())
                .promptVersion(promptVersion)
                .evidence(evidence)
                .confidence(confidence)
                .degraded(degraded)
                .cacheHit(false)
                .fallbackReason(fallbackReason)
                .build();
    }

    private int normalizeLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(10, limit);
    }

    private int candidateLimit(int requestedLimit) {
        return Math.max(20, requestedLimit * 3);
    }

    private ShopRecommendResult limitRecommendItems(ShopRecommendResult recommend, int requestedLimit) {
        if (recommend == null || recommend.getItems() == null) {
            return recommend;
        }
        List<com.hmdp.dto.ai.ShopRecommendationItem> items = recommend.getItems();
        if (items.size() > requestedLimit) {
            items = new ArrayList<>(items.subList(0, requestedLimit));
            recommend.setItems(items);
        }
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setRank(i + 1);
        }
        return recommend;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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
