package com.hmdp.controller;

import com.hmdp.config.ChatMemoryKeyManager;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.service.ai.ShopAIService;
import com.hmdp.service.ShopSummaryService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/shop-summary")
@Slf4j
public class ShopSummaryController {

    @Autowired
    private ShopSummaryService shopSummaryService;

    @Autowired
    private ShopAIService shopAIService;

    @Autowired
    private ChatMemoryKeyManager keyManager;

    // ========== 智能对话入口（主要接口） ==========

    /**
     * 智能对话 - 万能接口，支持所有功能
     * 用户可以用自然语言表达任何需求
     */
    @PostMapping("/ai/chat")
    public Result smartChat(
            @RequestParam(defaultValue = "default") String sessionId,
            @RequestParam String message
    ) {
        try {
            log.info("智能对话请求 - 会话: {}, 消息: {}", sessionId, message);

            String userId = getCurrentUserId();
            String fullSessionId = userId + "_" + sessionId;

            // AI会自动识别用户意图并调用相应的Tool
            String response = shopAIService.chat(fullSessionId, message);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("response", response);
            resultData.put("sessionId", fullSessionId);
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);

        } catch (Exception e) {
            log.error("智能对话失败 - 会话: {}, 消息: {}", sessionId, message, e);
            return Result.fail("对话失败，请稍后重试");
        }
    }



    /**
     * 生成店铺总结（不支持记忆，用于快速预览）
     * 传统API - 基础总结（保留给需要结构化数据的场景）
     */
    @GetMapping("/{shopId}")
    public Result getShopSummary(@PathVariable Long shopId) {
        try {
            ShopSummaryResult summary = shopSummaryService.generateShopSummary(shopId);

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
    public Result getShopSummaryWithMemory(@PathVariable Long shopId) {
        try {
            String userId = getCurrentUserId();
            ShopSummaryResult summary = shopSummaryService.generateShopSummary(shopId, userId);
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
    public Result smartAnalyzeShop(@PathVariable Long shopId) {
        try {
            String userId = getCurrentUserId();
            String sessionId = "quick_" + System.currentTimeMillis();
            String message = "请详细分析一下店铺" + shopId + "的情况";

            String response = shopAIService.chat(userId + "_" + sessionId, message);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("response", response);
            resultData.put("shopId", shopId);
            resultData.put("type", "smart_analysis");
            resultData.put("timestamp", System.currentTimeMillis());

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
    public Result smartAskAboutShop(
            @PathVariable Long shopId,
            @RequestParam String question) {
        try {
            String userId = getCurrentUserId();
            String sessionId = "qa_" + shopId + "_" + System.currentTimeMillis();
            String message = "关于店铺" + shopId + "：" + question;

            String response = shopAIService.chat(userId + "_" + sessionId, message);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("response", response);
            resultData.put("shopId", shopId);
            resultData.put("question", question);
            resultData.put("type", "smart_qa");
            resultData.put("timestamp", System.currentTimeMillis());

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

            String response = shopAIService.chat(userId + "_" + sessionId, message);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("response", response);
            resultData.put("shopId1", shopId1);
            resultData.put("shopId2", shopId2);
            resultData.put("aspect", aspect);
            resultData.put("type", "smart_compare");
            resultData.put("timestamp", System.currentTimeMillis());

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

            String response = shopAIService.chat(userId + "_" + sessionId, message);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("response", response);
            resultData.put("userPreference", userPreference);
            resultData.put("category", category);
            resultData.put("limit", limit);
            resultData.put("type", "smart_recommend");
            resultData.put("timestamp", System.currentTimeMillis());

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
    public Result askAboutShop(
            @PathVariable Long shopId,
            @RequestParam String question) {
        try {
            String userId = getCurrentUserId();
            String response = shopSummaryService.askAboutShop(userId, shopId, question);
            String memoryKey = keyManager.buildShopQAKey(shopId, userId);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("response", response);
            resultData.put("shopId", shopId);
            resultData.put("memoryKey", memoryKey);
            resultData.put("question", question);
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("店铺问答失败, shopId: {}, question: {}", shopId, question, e);
            return Result.fail("问答失败，请稍后重试");
        }
    }

    /**
     * 店铺对比分析（支持记忆）
     */
    @PostMapping("/compare")
    public Result compareShops(
            @RequestParam Long shopId1,
            @RequestParam Long shopId2,
            @RequestParam(required = false) String aspect,
            @RequestParam(defaultValue = "default") String sessionId) {
        try {
            String userId = getCurrentUserId();
            String comparison = shopSummaryService.compareShops(userId, sessionId, shopId1, shopId2, aspect);
            String memoryKey = keyManager.buildShopCompareKey(userId, sessionId);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("comparison", comparison);
            resultData.put("shopId1", shopId1);
            resultData.put("shopId2", shopId2);
            resultData.put("aspect", aspect);
            resultData.put("memoryKey", memoryKey);
            resultData.put("sessionId", sessionId);
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("店铺对比失败, shopId1: {}, shopId2: {}", shopId1, shopId2, e);
            return Result.fail("对比分析失败，请稍后重试");
        }
    }

    /**
     * 基于用户偏好的推荐（支持记忆）
     */
    @PostMapping("/recommend")
    public Result recommendShops(
            @RequestParam String userPreference,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "5") Integer limit) {
        try {
            String userId = getCurrentUserId();
            String recommendations = shopSummaryService.recommendShops(userId, userPreference, category, limit);
            String memoryKey = keyManager.buildShopRecommendKey(userId);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("recommendations", recommendations);
            resultData.put("userPreference", userPreference);
            resultData.put("category", category);
            resultData.put("memoryKey", memoryKey);
            resultData.put("limit", limit);
            resultData.put("timestamp", System.currentTimeMillis());

            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("推荐失败, preference: {}", userPreference, e);
            return Result.fail("推荐失败，请稍后重试");
        }
    }

    // ========== 记忆管理相关接口 ==========

    /**
     * 清除店铺问答记忆
     */
    @DeleteMapping("/{shopId}/memory/qa")
    public Result clearShopQAMemory(@PathVariable Long shopId) {
        try {
            String userId = getCurrentUserId();
            shopSummaryService.clearShopQAMemory(userId, shopId);

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
    public Result clearShopSummaryMemory(@PathVariable Long shopId) {
        try {
            String userId = getCurrentUserId();
            shopSummaryService.clearShopSummaryMemory(userId, shopId);

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
    public Result clearRecommendMemory() {
        try {
            String userId = getCurrentUserId();
            shopSummaryService.clearRecommendMemory(userId);

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
    public Result clearAllMemory() {
        try {
            String userId = getCurrentUserId();
            Map<String, Integer> result = shopSummaryService.clearAllUserMemory(userId);

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
    public Result getMemoryStats() {
        try {
            Map<String, Map<String, Integer>> stats = shopSummaryService.getMemoryStats();

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

            boolean exists = shopSummaryService.hasMemory(memoryKey);
            int messageCount = shopSummaryService.getMemoryMessageCount(memoryKey);
            long ttl = shopSummaryService.getMemoryTtl(memoryKey);

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

            shopSummaryService.refreshMemoryTtl(memoryKey);

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
    public Result adminCleanupMemory(@PathVariable String functionType) {
        try {
            // 这里应该添加管理员权限检查
            if (!isAdmin()) {
                return Result.fail("权限不足");
            }

            int count = shopSummaryService.cleanupMemoryByFunction(functionType);

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

    /**
     * 检查是否为管理员
     */
    private boolean isAdmin() {
        try {
            // 实现管理员权限检查逻辑
            // 这里需要根据你的权限系统来实现
            return false; // 临时返回false
        } catch (Exception e) {
            log.error("检查管理员权限失败", e);
            return false;
        }
    }
}