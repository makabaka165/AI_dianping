package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.ai.application.ShopAIApplicationService;
import com.hmdp.config.ChatMemoryKeyManager;
import com.hmdp.dto.Result;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAskRequest;
import com.hmdp.dto.ai.ShopChatRequest;
import com.hmdp.dto.ai.ShopCompareRequest;
import com.hmdp.dto.ai.ShopRecommendRequest;
import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.service.ShopSummaryService;
import com.hmdp.utils.AiLogSanitizer;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/shop-summary")
@Slf4j
public class ShopSummaryController {

    @Autowired
    private ShopSummaryService shopSummaryService;

    @Autowired
    private ShopAIApplicationService shopAIApplicationService;

    @Autowired
    private ChatMemoryKeyManager keyManager;

    // ========== 智能对话入口（主要接口） ==========

    /**
     * 智能对话 - 万能接口，支持所有功能
     * 用户可以用自然语言表达任何需求
     */
    @PostMapping("/ai/chat")
    @SaCheckPermission("ai:chat")
    public Result smartChat(@Valid @RequestBody ShopChatRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return Result.fail("消息不能为空");
        }
        String sessionId = normalizeSessionId(request.getSessionId());
        String message = request.getMessage();
        try {
            log.info("智能对话请求 - 会话: {}, 消息: {}", sessionId, AiLogSanitizer.safe(message));

            String userId = getCurrentUserId();
            ShopAIResponse resultData = shopAIApplicationService.chat(
                    userId, sessionId, message, request.getShopId(), "/api/shop-summary/ai/chat");
            return Result.ok(resultData);

        } catch (Exception e) {
            log.error("智能对话失败 - 会话: {}, 消息: {}", sessionId, message, e);
            return Result.fail("对话失败，请稍后重试");
        }
    }

    @PostMapping(value = "/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SaCheckPermission("ai:chat")
    public Flux<String> smartChatStream(@Valid @RequestBody ShopChatRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return Flux.just("消息不能为空");
        }
        String sessionId = normalizeSessionId(request.getSessionId());
        String userId = getCurrentUserId();
        return shopAIApplicationService.chatStream(userId, sessionId, request.getMessage(), "/api/shop-summary/ai/chat/stream");
    }



    /**
     * 生成店铺总结（不支持记忆，用于快速预览）
     * 传统API - 基础总结（保留给需要结构化数据的场景）
     */
    @GetMapping("/{shopId}")
    public Result getShopSummary(@PathVariable Long shopId) {
        try {
            ShopSummaryResult summary = shopAIApplicationService.summary(
                    "anonymous_user", shopId, false, "/api/shop-summary/{shopId}");

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("summary", summary);
            resultData.put("message", "店铺总结已生成");
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("生成店铺总结失败, shopId: {}", shopId, e);
            return Result.fail("生成总结失败，请稍后重试");
        }
    }

    /**
     * 生成店铺总结（支持记忆，用于后续对话）
     */
    @PostMapping("/{shopId}/with-memory")
    @SaCheckLogin
    public Result getShopSummaryWithMemory(@PathVariable Long shopId) {
        try {
            String userId = getCurrentUserId();
            ShopSummaryResult summary = shopAIApplicationService.summary(
                    userId, shopId, true, "/api/shop-summary/{shopId}/with-memory");
            String memoryKey = keyManager.buildShopSummaryKey(shopId, userId);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("summary", summary);
            resultData.put("memoryKey", memoryKey);
            resultData.put("message", "店铺总结已生成并保存到记忆中，您可以继续询问相关问题");
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("生成带记忆的店铺总结失败, shopId: {}", shopId, e);
            return Result.fail("生成总结失败，请稍后重试");
        }
    }

    /**
     * 快捷智能分析
     */
    @GetMapping("/ai/analyze/{shopId}")  // 改为 /ai/analyze/{shopId}
    @SaCheckPermission("ai:chat")
    public Result smartAnalyzeShop(@PathVariable Long shopId) {
        try {
            String userId = getCurrentUserId();
            String sessionId = "quick_" + System.currentTimeMillis();
            String message = "请详细分析一下店铺" + shopId + "的情况";
            ShopAIResponse resultData = shopAIApplicationService.chat(
                    userId, sessionId, message, shopId, "/api/shop-summary/ai/analyze");
            resultData.setShopId(shopId);
            return Result.ok(resultData);

        } catch (Exception e) {
            log.error("智能分析店铺失败, shopId: {}", shopId, e);
            return Result.fail("智能分析失败，请稍后重试");
        }
    }

    /**
     * 智能问答
     */
    @PostMapping("/ai/ask/{shopId}")  // 改为 /ai/ask/{shopId}
    @SaCheckPermission("ai:chat")
    public Result smartAskAboutShop(
            @PathVariable Long shopId,
            @RequestParam String question) {
        try {
            String userId = getCurrentUserId();
            String sessionId = "qa_" + shopId + "_" + System.currentTimeMillis();
            String message = "关于店铺" + shopId + "：" + question;
            ShopAIResponse resultData = shopAIApplicationService.chat(
                    userId, sessionId, message, shopId, "/api/shop-summary/ai/ask");
            resultData.setShopId(shopId);
            return Result.ok(resultData);

        } catch (Exception e) {
            log.error("智能问答失败, shopId: {}, question: {}", shopId, question, e);
            return Result.fail("智能问答失败，请稍后重试");
        }
    }

    /**
     * 智能对比
     */
    @PostMapping("/ai/compare")  // 改为 /ai/compare
    @SaCheckPermission("ai:chat")
    public Result smartCompareShops(
            @RequestParam Long shopId1,
            @RequestParam Long shopId2,
            @RequestParam(required = false) String aspect) {
        try {
            String userId = getCurrentUserId();
            String sessionId = "compare_" + System.currentTimeMillis();

            String message = "请对比店铺" + shopId1 + "和店铺" + shopId2;
            if (aspect != null && !aspect.trim().isEmpty()) {
                message += "在" + aspect + "方面";
            }
            message += "的表现";
            ShopAIResponse resultData = shopAIApplicationService.chat(
                    userId, sessionId, message, null, "/api/shop-summary/ai/compare");
            return Result.ok(resultData);

        } catch (Exception e) {
            log.error("智能对比失败, shopId1: {}, shopId2: {}", shopId1, shopId2, e);
            return Result.fail("智能对比失败，请稍后重试");
        }
    }

    /**
     * 智能推荐
     */
    @PostMapping("/ai/recommend")  // 改为 /ai/recommend
    @SaCheckPermission("ai:chat")
    public Result smartRecommendShops(
            @RequestParam String userPreference,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "3") Integer limit) {
        try {
            String userId = getCurrentUserId();
            String sessionId = "recommend_" + System.currentTimeMillis();

            String message = "请根据我的偏好推荐店铺：" + userPreference;
            if (category != null && !category.trim().isEmpty()) {
                message += "，类型：" + category;
            }
            message += "，推荐" + limit + "家";
            ShopAIResponse resultData = shopAIApplicationService.chat(
                    userId, sessionId, message, null, "/api/shop-summary/ai/recommend");
            return Result.ok(resultData);

        } catch (Exception e) {
            log.error("智能推荐失败, preference: {}", userPreference, e);
            return Result.fail("智能推荐失败，请稍后重试");
        }
    }

    /**
     * 生成高质量博客总结（支持记忆）
     */
    @GetMapping("/{shopId}/quality")
    @SaCheckLogin
    public Result getQualitySummary(
            @PathVariable Long shopId,
            @RequestParam(defaultValue = "5") Integer minLiked,
            @RequestParam(defaultValue = "10") Integer limit) {
        try {
            String userId = getCurrentUserId();
            ShopSummaryResult summary = shopSummaryService.generateQualitySummary(shopId, minLiked, limit, userId);
            String memoryKey = keyManager.buildShopSummaryKey(shopId, userId);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("summary", summary);
            resultData.put("memoryKey", memoryKey);
            resultData.put("minLiked", minLiked);
            resultData.put("limit", limit);
            resultData.put("message", "高质量评价总结已生成，您可以继续询问相关问题");
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("生成高质量总结失败, shopId: {}", shopId, e);
            return Result.fail("生成总结失败，请稍后重试");
        }
    }

    /**
     * 智能店铺问答（核心对话功能）
     */
    @PostMapping("/{shopId}/ask")
    @SaCheckLogin
    public Result askAboutShop(
            @PathVariable Long shopId,
            @Valid @RequestBody ShopAskRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            return Result.fail("问题不能为空");
        }
        try {
            String userId = getCurrentUserId();
            ShopAIResponse resultData = shopAIApplicationService.ask(
                    userId,
                    normalizeSessionId(request.getSessionId()),
                    shopId,
                    request.getQuestion(),
                    "/api/shop-summary/{shopId}/ask");

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("店铺问答失败, shopId: {}", shopId, e);
            return Result.fail("问答失败，请稍后重试");
        }
    }

    /**
     * 店铺对比分析（支持记忆）
     */
    @PostMapping("/compare")
    @SaCheckLogin
    public Result compareShops(@RequestBody ShopCompareRequest request) {
        if (request == null || request.getShopId1() == null || request.getShopId2() == null) {
            return Result.fail("店铺ID不能为空");
        }
        try {
            String userId = getCurrentUserId();
            ShopAIResponse resultData = shopAIApplicationService.compare(
                    userId,
                    normalizeSessionId(request.getSessionId()),
                    request.getShopId1(),
                    request.getShopId2(),
                    request.getAspect(),
                    "/api/shop-summary/compare");

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("店铺对比失败", e);
            return Result.fail("对比分析失败，请稍后重试");
        }
    }

    /**
     * 基于用户偏好的推荐（支持记忆）
     */
    @PostMapping("/recommend")
    @SaCheckLogin
    public Result recommendShops(@Valid @RequestBody ShopRecommendRequest request) {
        if (request == null || request.getUserPreference() == null || request.getUserPreference().trim().isEmpty()) {
            return Result.fail("用户偏好不能为空");
        }
        try {
            String userId = getCurrentUserId();
            ShopAIResponse resultData = shopAIApplicationService.recommend(
                    userId,
                    normalizeSessionId(request.getSessionId()),
                    request.getUserPreference(),
                    request.getCategory(),
                    request.getLimit(),
                    "/api/shop-summary/recommend");

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("推荐失败, preference: {}", AiLogSanitizer.safe(request == null ? null : request.getUserPreference()), e);
            return Result.fail("推荐失败，请稍后重试");
        }
    }

    // ========== 记忆管理相关接口 ==========

    /**
     * 清除店铺问答记忆
     */
    @DeleteMapping("/{shopId}/memory/qa")
    @SaCheckLogin
    public Result clearShopQAMemory(@PathVariable Long shopId) {
        try {
            String userId = getCurrentUserId();
            shopAIApplicationService.clearShopQAMemory(userId, shopId);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("message", "店铺问答记忆已清除");
            resultData.put("shopId", shopId);
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("清除店铺问答记忆失败, shopId: {}", shopId, e);
            return Result.fail("清除记忆失败");
        }
    }

    /**
     * 清除店铺总结记忆
     */
    @DeleteMapping("/{shopId}/memory/summary")
    @SaCheckLogin
    public Result clearShopSummaryMemory(@PathVariable Long shopId) {
        try {
            String userId = getCurrentUserId();
            shopAIApplicationService.clearShopSummaryMemory(userId, shopId);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("message", "店铺总结记忆已清除");
            resultData.put("shopId", shopId);
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("清除店铺总结记忆失败, shopId: {}", shopId, e);
            return Result.fail("清除记忆失败");
        }
    }

    /**
     * 清除推荐记忆
     */
    @DeleteMapping("/memory/recommend")
    @SaCheckLogin
    public Result clearRecommendMemory() {
        try {
            String userId = getCurrentUserId();
            shopAIApplicationService.clearRecommendMemory(userId);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("message", "推荐记忆已清除");
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("清除推荐记忆失败");
            return Result.fail("清除记忆失败");
        }
    }

    /**
     * 清除用户所有记忆
     */
    @DeleteMapping("/memory/all")
    @SaCheckLogin
    public Result clearAllMemory() {
        try {
            String userId = getCurrentUserId();
            Map<String, Integer> result = shopAIApplicationService.clearAllUserMemory(userId);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("message", "所有记忆已清除");
            resultData.put("details", result);
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("清除所有记忆失败", e);
            return Result.fail("清除记忆失败");
        }
    }

    /**
     * 获取记忆统计信息
     */
    @GetMapping("/memory/stats")
    @SaCheckPermission("ai:memory:manage")
    public Result getMemoryStats() {
        try {
            Map<String, Map<String, Integer>> stats = shopAIApplicationService.getMemoryStats();

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("stats", stats);
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("获取记忆统计失败", e);
            return Result.fail("获取统计失败");
        }
    }

    /**
     * 检查特定记忆状态
     */
    @GetMapping("/memory/{shopId}/status")
    @SaCheckLogin
    public Result getMemoryStatus(
            @PathVariable Long shopId,
            @RequestParam String type) { // qa, summary, compare, recommend
        try {
            String userId = getCurrentUserId();
            String memoryKey;

            switch (type.toLowerCase()) {
                case "qa":
                    memoryKey = keyManager.buildShopQAKey(shopId, userId);
                    break;
                case "summary":
                    memoryKey = keyManager.buildShopSummaryKey(shopId, userId);
                    break;
                case "recommend":
                    memoryKey = keyManager.buildShopRecommendKey(userId);
                    break;
                default:
                    return Result.fail("不支持的记忆类型: " + type);
            }

            boolean exists = shopAIApplicationService.hasMemory(memoryKey);
            int messageCount = shopAIApplicationService.getMemoryMessageCount(memoryKey);
            long ttl = shopAIApplicationService.getMemoryTtl(memoryKey);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("memoryKey", memoryKey);
            resultData.put("type", type);
            resultData.put("exists", exists);
            resultData.put("messageCount", messageCount);
            resultData.put("ttlSeconds", ttl);
            resultData.put("ttlMinutes", ttl > 0 ? ttl / 60 : -1);
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("获取记忆状态失败, shopId: {}, type: {}", shopId, type, e);
            return Result.fail("获取记忆状态失败");
        }
    }

    /**
     * 刷新记忆过期时间
     */
    @PostMapping("/memory/{shopId}/refresh")
    @SaCheckLogin
    public Result refreshMemory(
            @PathVariable Long shopId,
            @RequestParam String type) {
        try {
            String userId = getCurrentUserId();
            String memoryKey;

            switch (type.toLowerCase()) {
                case "qa":
                    memoryKey = keyManager.buildShopQAKey(shopId, userId);
                    break;
                case "summary":
                    memoryKey = keyManager.buildShopSummaryKey(shopId, userId);
                    break;
                case "recommend":
                    memoryKey = keyManager.buildShopRecommendKey(userId);
                    break;
                default:
                    return Result.fail("不支持的记忆类型: " + type);
            }

            shopAIApplicationService.refreshMemoryTtl(memoryKey);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("message", "记忆过期时间已刷新");
            resultData.put("memoryKey", memoryKey);
            resultData.put("type", type);
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("刷新记忆失败, shopId: {}, type: {}", shopId, type, e);
            return Result.fail("刷新记忆失败");
        }
    }

    // ========== 管理员功能 ==========

    /**
     * 批量清理功能记忆（管理员功能）
     */
    @DeleteMapping("/admin/memory/{functionType}")
    @SaCheckPermission("ai:memory:manage")
    public Result adminCleanupMemory(@PathVariable String functionType) {
        try {
            int count = shopAIApplicationService.cleanupMemoryByFunction(functionType);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("message", "批量清理完成");
            resultData.put("functionType", functionType);
            resultData.put("cleanedCount", count);
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("批量清理记忆失败, functionType: {}", functionType, e);
            return Result.fail("批量清理失败");
        }
    }

    // ========== 工具方法 ==========

    /**
     * 获取当前用户ID
     */
    private String getCurrentUserId() {
        // 从UserHolder或JWT中获取用户ID
        // 这里假设你有UserHolder工具类
        try {
            return UserHolder.getUser().getId().toString();
        } catch (Exception e) {
            // 如果获取不到用户ID，使用默认值或抛出异常
            log.warn("无法获取当前用户ID，使用默认用户");
            return "anonymous_user";
        }
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return "default";
        }
        return sessionId.trim();
    }

}
