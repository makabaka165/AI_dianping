package com.hmdp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiRequestContext;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.ShopContextAssembler;
import com.hmdp.service.ShopReviewEvidenceRetriever;
import com.hmdp.service.ShopStatsService;
import com.hmdp.utils.LocalCacheManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ShopTool {

    private static final int MAX_LIMIT = 10;

    @Resource
    private ShopReviewEvidenceRetriever evidenceRetriever;

    @Resource
    private ShopContextAssembler contextAssembler;

    @Resource
    private LocalCacheManager localCacheManager;

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private ShopStatsService shopStatsService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool("检查店铺是否存在，并返回公开统计信息")
    public String checkShopExists(@P("店铺ID，必须是正整数") Long shopId) {
        if (!validShopId(shopId)) {
            return error("INVALID_SHOP_ID", "店铺ID必须是正整数");
        }
        String userId = getCurrentUserId();
        if (userId == null) {
            return error("AUTH_CONTEXT_MISSING", "缺少用户上下文，无法调用工具");
        }
        if (!allowCall(userId, "checkShopExists", 20, 5)) {
            return error("RATE_LIMITED", "调用过于频繁，请稍后再试");
        }
        Map<String, Object> data = new HashMap<>();
        boolean exists = shopStatsService.shopExists(shopId);
        data.put("success", true);
        data.put("shopId", shopId);
        data.put("exists", exists);
        data.put("reviewCount", exists ? shopStatsService.getShopReviewCount(shopId) : 0);
        return json(data);
    }

    @Tool("获取店铺画像快照，只返回已有缓存或确定性统计信息")
    public String getShopProfile(@P("店铺ID，必须是正整数") Long shopId) {
        if (!validShopId(shopId)) {
            return error("INVALID_SHOP_ID", "店铺ID必须是正整数");
        }
        String userId = getCurrentUserId();
        if (userId == null) {
            return error("AUTH_CONTEXT_MISSING", "缺少用户上下文，无法调用工具");
        }
        if (!allowCall(userId, "getShopProfile", 10, 3)) {
            return error("RATE_LIMITED", "调用过于频繁，请稍后再试");
        }
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            return error("SHOP_NOT_FOUND", "店铺不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("success", true);
        data.put("shopId", shopId);
        data.put("shopName", shop.getName());
        data.put("area", shop.getArea());
        data.put("avgPrice", shop.getAvgPrice());
        data.put("sold", shop.getSold());
        data.put("comments", shop.getComments());
        data.put("score", shop.getScore());
        data.put("openHours", shop.getOpenHours());
        data.put("reviewCount", shopStatsService.getShopReviewCount(shopId));
        return json(data);
    }

    @Tool("检索店铺评价证据，用于回答店铺相关问题")
    public String getShopReviewEvidence(
            @P("店铺ID，必须是正整数") Long shopId,
            @P("用户问题或检索词") String query,
            @P("最多返回条数，1到10") Integer limit) {
        if (!validShopId(shopId)) {
            return error("INVALID_SHOP_ID", "店铺ID必须是正整数");
        }
        String userId = getCurrentUserId();
        if (userId == null) {
            return error("AUTH_CONTEXT_MISSING", "缺少用户上下文，无法调用工具");
        }
        if (!allowCall(userId, "getShopReviewEvidence", 15, 5)) {
            return error("RATE_LIMITED", "调用过于频繁，请稍后再试");
        }
        List<EvidenceItem> evidence = evidenceRetriever.retrieve(shopId, query, null, normalizeLimit(limit, 5));
        Map<String, Object> data = new HashMap<>();
        data.put("success", true);
        data.put("shopId", shopId);
        data.put("evidence", evidence);
        data.put("evidenceCount", evidence.size());
        return json(data);
    }

    @Tool("获取两个店铺在同一维度下的对比证据")
    public String getShopComparisonEvidence(
            @P("第一个店铺ID") Long shopId1,
            @P("第二个店铺ID") Long shopId2,
            @P("对比维度，如服务、环境、性价比") String aspect) {
        if (!validShopId(shopId1) || !validShopId(shopId2)) {
            return error("INVALID_SHOP_ID", "店铺ID必须是正整数");
        }
        String userId = getCurrentUserId();
        if (userId == null) {
            return error("AUTH_CONTEXT_MISSING", "缺少用户上下文，无法调用工具");
        }
        if (!allowCall(userId, "getShopComparisonEvidence", 5, 2)) {
            return error("RATE_LIMITED", "调用过于频繁，请稍后再试");
        }
        ShopAnalysisContext first = contextAssembler.buildForCompare(shopId1, "店铺对比", aspect);
        ShopAnalysisContext second = contextAssembler.buildForCompare(shopId2, "店铺对比", aspect);
        Map<String, Object> data = new HashMap<>();
        data.put("success", true);
        data.put("aspect", aspect);
        data.put("shop1", first);
        data.put("shop2", second);
        return json(data);
    }

    @Tool("根据用户偏好生成推荐候选说明，不直接编造不存在的店铺")
    public String findRecommendCandidates(
            @P("用户偏好描述") String userPreference,
            @P("店铺类型，可为空") String category,
            @P("推荐数量，1到10") Integer limit) {
        String userId = getCurrentUserId();
        if (userId == null) {
            return error("AUTH_CONTEXT_MISSING", "缺少用户上下文，无法调用工具");
        }
        if (!allowCall(userId, "findRecommendCandidates", 5, 2)) {
            return error("RATE_LIMITED", "调用过于频繁，请稍后再试");
        }
        int safeLimit = normalizeLimit(limit, 5);
        List<Shop> candidates = shopMapper.selectRecommendCandidates(category, safeLimit);
        Map<String, Object> data = new HashMap<>();
        data.put("success", true);
        data.put("preference", userPreference);
        data.put("category", category);
        data.put("limit", safeLimit);
        data.put("candidates", toPublicCandidateViews(candidates));
        data.put("candidateCount", candidates == null ? 0 : candidates.size());
        data.put("note", "候选店铺按评分、评论数和销量排序；最终推荐理由需结合用户偏好与店铺证据生成。");
        return json(data);
    }

    private List<Map<String, Object>> toPublicCandidateViews(List<Shop> candidates) {
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream()
                .map(this::toPublicCandidateView)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toPublicCandidateView(Shop shop) {
        Map<String, Object> view = new HashMap<>();
        if (shop == null) {
            return view;
        }
        view.put("shopId", shop.getId());
        view.put("shopName", shop.getName());
        view.put("typeId", shop.getTypeId());
        view.put("area", shop.getArea());
        view.put("avgPrice", shop.getAvgPrice());
        view.put("sold", shop.getSold());
        view.put("comments", shop.getComments());
        view.put("score", shop.getScore());
        view.put("openHours", shop.getOpenHours());
        return view;
    }

    private boolean allowCall(String userId, String toolName, int totalLimit, int minuteLimit) {
        return localCacheManager.checkAndIncrementUserCallCount(userId, toolName, totalLimit)
                && localCacheManager.checkAndIncrementTimeBasedCallCount(userId, toolName, 60000, minuteLimit);
    }

    private String getCurrentUserId() {
        String contextUserId = AiRequestContext.currentUserId();
        if (contextUserId != null && !contextUserId.trim().isEmpty()) {
            return contextUserId;
        }
        return null;
    }

    private boolean validShopId(Long shopId) {
        return shopId != null && shopId > 0;
    }

    private int normalizeLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(MAX_LIMIT, limit);
    }

    private String error(String code, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("success", false);
        data.put("errorCode", code);
        data.put("message", message);
        return json(data);
    }

    private String json(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Serialize tool response failed", e);
            return "{\"success\":false,\"errorCode\":\"SERIALIZE_ERROR\"}";
        }
    }
}
