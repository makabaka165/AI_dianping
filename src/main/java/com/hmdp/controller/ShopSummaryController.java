package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.ai.application.ShopAIAdminApplicationService;
import com.hmdp.ai.application.ShopAIApplicationService;
import com.hmdp.ai.application.ShopAIMemoryApplicationService;
import com.hmdp.config.ChatMemoryKeyManager;
import com.hmdp.dto.Result;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAIStreamEvent;
import com.hmdp.dto.ai.ShopAskRequest;
import com.hmdp.dto.ai.ShopChatRequest;
import com.hmdp.dto.ai.ShopCompareRequest;
import com.hmdp.dto.ai.ShopRecommendRequest;
import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.service.CurrentUserService;
import com.hmdp.utils.AiLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/shop-summary")
@Slf4j
public class ShopSummaryController {

    @Autowired
    private ShopAIApplicationService shopAIApplicationService;

    @Autowired
    private ShopAIMemoryApplicationService shopAIMemoryApplicationService;

    @Autowired
    private ShopAIAdminApplicationService shopAIAdminApplicationService;

    @Autowired
    private ChatMemoryKeyManager keyManager;

    @Autowired
    private CurrentUserService currentUserService;

    @PostMapping("/ai/chat")
    @SaCheckPermission("ai:chat")
    public Result smartChat(@Valid @RequestBody ShopChatRequest request) {
        if (request == null || isBlank(request.getMessage())) {
            return Result.fail("消息不能为空");
        }
        String sessionId = normalizeSessionId(request.getSessionId());
        String message = request.getMessage();
        try {
            log.info("智能对话请求 - sessionId={}, message={}", sessionId, AiLogSanitizer.safe(message));
            ShopAIResponse resultData = shopAIApplicationService.chat(
                    getCurrentUserId(), sessionId, message, request.getShopId(), "/api/shop-summary/ai/chat");
            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("智能对话失败 - sessionId={}, message={}", sessionId, AiLogSanitizer.safe(message), e);
            return Result.fail("对话失败，请稍后重试");
        }
    }

    @PostMapping(value = "/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SaCheckPermission("ai:chat")
    public Flux<ServerSentEvent<ShopAIStreamEvent>> smartChatStream(@Valid @RequestBody ShopChatRequest request) {
        if (request == null || isBlank(request.getMessage())) {
            return Flux.just(streamError("消息不能为空"));
        }
        return shopAIApplicationService.chatStream(
                getCurrentUserId(),
                normalizeSessionId(request.getSessionId()),
                request.getMessage(),
                request.getShopId(),
                "/api/shop-summary/ai/chat/stream");
    }

    @GetMapping("/{shopId}")
    @SaCheckLogin
    public Result getShopSummary(@PathVariable Long shopId) {
        try {
            String userId = getCurrentUserId();
            ShopSummaryResult summary = shopAIApplicationService.summary(
                    userId, shopId, false, "/api/shop-summary/{shopId}");
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("summary", summary);
            appendSummaryMetadata(resultData, summary);
            resultData.put("message", "店铺总结已生成");
            resultData.put("timestamp", System.currentTimeMillis());
            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("生成店铺总结失败, shopId={}", shopId, e);
            return Result.fail("生成总结失败，请稍后重试");
        }
    }

    @PostMapping("/{shopId}/with-memory")
    @SaCheckLogin
    public Result getShopSummaryWithMemory(@PathVariable Long shopId) {
        try {
            String userId = getCurrentUserId();
            ShopSummaryResult summary = shopAIApplicationService.summary(
                    userId, shopId, true, "/api/shop-summary/{shopId}/with-memory");
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("summary", summary);
            appendSummaryMetadata(resultData, summary);
            resultData.put("memoryKey", summaryMemoryKey(summary, shopId, userId));
            resultData.put("message", "店铺总结已生成并保存到记忆中");
            resultData.put("timestamp", System.currentTimeMillis());
            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("生成带记忆的店铺总结失败, shopId={}", shopId, e);
            return Result.fail("生成总结失败，请稍后重试");
        }
    }

    @GetMapping("/{shopId}/quality")
    @SaCheckLogin
    public Result getQualitySummary(
            @PathVariable Long shopId,
            @RequestParam(defaultValue = "5") Integer minLiked,
            @RequestParam(defaultValue = "10") Integer limit) {
        try {
            String userId = getCurrentUserId();
            ShopSummaryResult summary = shopAIApplicationService.qualitySummary(
                    userId, shopId, minLiked, limit, true, "/api/shop-summary/{shopId}/quality");
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("summary", summary);
            appendSummaryMetadata(resultData, summary);
            resultData.put("memoryKey", summaryMemoryKey(summary, shopId, userId));
            resultData.put("minLiked", minLiked);
            resultData.put("limit", limit);
            resultData.put("message", "高质量评价总结已生成");
            resultData.put("timestamp", System.currentTimeMillis());
            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("生成高质量总结失败, shopId={}", shopId, e);
            return Result.fail("生成总结失败，请稍后重试");
        }
    }

    @PostMapping("/{shopId}/ask")
    @SaCheckLogin
    public Result askAboutShop(@PathVariable Long shopId, @Valid @RequestBody ShopAskRequest request) {
        if (request == null || isBlank(request.getQuestion())) {
            return Result.fail("问题不能为空");
        }
        try {
            ShopAIResponse resultData = shopAIApplicationService.ask(
                    getCurrentUserId(),
                    normalizeSessionId(request.getSessionId()),
                    shopId,
                    request.getQuestion(),
                    "/api/shop-summary/{shopId}/ask");
            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("店铺问答失败, shopId={}", shopId, e);
            return Result.fail("问答失败，请稍后重试");
        }
    }

    @PostMapping("/compare")
    @SaCheckLogin
    public Result compareShops(@Valid @RequestBody ShopCompareRequest request) {
        if (request == null || request.getShopId1() == null || request.getShopId2() == null) {
            return Result.fail("店铺ID不能为空");
        }
        try {
            ShopAIResponse resultData = shopAIApplicationService.compare(
                    getCurrentUserId(),
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

    @PostMapping("/recommend")
    @SaCheckLogin
    public Result recommendShops(@Valid @RequestBody ShopRecommendRequest request) {
        if (request == null || isBlank(request.getUserPreference())) {
            return Result.fail("用户偏好不能为空");
        }
        try {
            ShopAIResponse resultData = shopAIApplicationService.recommend(
                    getCurrentUserId(),
                    normalizeSessionId(request.getSessionId()),
                    request.getUserPreference(),
                    request.getCategory(),
                    request.getLimit(),
                    "/api/shop-summary/recommend");
            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("推荐失败, preference={}", AiLogSanitizer.safe(request == null ? null : request.getUserPreference()), e);
            return Result.fail("推荐失败，请稍后重试");
        }
    }

    @DeleteMapping("/{shopId}/memory/qa")
    @SaCheckLogin
    public Result clearShopQAMemory(@PathVariable Long shopId) {
        try {
            shopAIMemoryApplicationService.clearShopQAMemory(getCurrentUserId(), shopId);
            return Result.ok(message("店铺问答记忆已清除", "shopId", shopId));
        } catch (Exception e) {
            log.error("清除店铺问答记忆失败, shopId={}", shopId, e);
            return Result.fail("清除记忆失败");
        }
    }

    @DeleteMapping("/{shopId}/memory/summary")
    @SaCheckLogin
    public Result clearShopSummaryMemory(@PathVariable Long shopId) {
        try {
            shopAIMemoryApplicationService.clearShopSummaryMemory(getCurrentUserId(), shopId);
            return Result.ok(message("店铺总结记忆已清除", "shopId", shopId));
        } catch (Exception e) {
            log.error("清除店铺总结记忆失败, shopId={}", shopId, e);
            return Result.fail("清除记忆失败");
        }
    }

    @DeleteMapping("/memory/recommend")
    @SaCheckLogin
    public Result clearRecommendMemory() {
        try {
            shopAIMemoryApplicationService.clearRecommendMemory(getCurrentUserId());
            return Result.ok(message("推荐记忆已清除", null, null));
        } catch (Exception e) {
            log.error("清除推荐记忆失败", e);
            return Result.fail("清除记忆失败");
        }
    }

    @DeleteMapping("/memory/all")
    @SaCheckLogin
    public Result clearAllMemory() {
        try {
            Map<String, Integer> result = shopAIMemoryApplicationService.clearAllUserMemory(getCurrentUserId());
            Map<String, Object> resultData = message("所有记忆已清除", "details", result);
            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("清除所有记忆失败", e);
            return Result.fail("清除记忆失败");
        }
    }

    @GetMapping("/memory/stats")
    @SaCheckPermission("ai:memory:manage")
    public Result getMemoryStats() {
        try {
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("stats", shopAIAdminApplicationService.getMemoryStats());
            resultData.put("timestamp", System.currentTimeMillis());
            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("获取记忆统计失败", e);
            return Result.fail("获取统计失败");
        }
    }

    @GetMapping("/memory/{shopId}/status")
    @SaCheckLogin
    public Result getMemoryStatus(@PathVariable Long shopId, @RequestParam String type) {
        try {
            String memoryKey = memoryKey(shopId, type, getCurrentUserId());
            if (memoryKey == null) {
                return Result.fail("不支持的记忆类型: " + type);
            }
            long ttl = shopAIMemoryApplicationService.getMemoryTtl(memoryKey);
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("memoryKey", memoryKey);
            resultData.put("type", type);
            resultData.put("exists", shopAIMemoryApplicationService.hasMemory(memoryKey));
            resultData.put("messageCount", shopAIMemoryApplicationService.getMemoryMessageCount(memoryKey));
            resultData.put("ttlSeconds", ttl);
            resultData.put("ttlMinutes", ttl > 0 ? ttl / 60 : -1);
            resultData.put("timestamp", System.currentTimeMillis());
            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("获取记忆状态失败, shopId={}, type={}", shopId, type, e);
            return Result.fail("获取记忆状态失败");
        }
    }

    @PostMapping("/memory/{shopId}/refresh")
    @SaCheckLogin
    public Result refreshMemory(@PathVariable Long shopId, @RequestParam String type) {
        try {
            String memoryKey = memoryKey(shopId, type, getCurrentUserId());
            if (memoryKey == null) {
                return Result.fail("不支持的记忆类型: " + type);
            }
            shopAIMemoryApplicationService.refreshMemoryTtl(memoryKey);
            return Result.ok(message("记忆过期时间已刷新", "memoryKey", memoryKey));
        } catch (Exception e) {
            log.error("刷新记忆失败, shopId={}, type={}", shopId, type, e);
            return Result.fail("刷新记忆失败");
        }
    }

    @DeleteMapping("/admin/memory/{functionType}")
    @SaCheckPermission("ai:memory:manage")
    public Result adminCleanupMemory(@PathVariable String functionType) {
        try {
            int count = shopAIAdminApplicationService.cleanupMemoryByFunction(functionType);
            Map<String, Object> resultData = message("批量清理完成", "functionType", functionType);
            resultData.put("cleanedCount", count);
            return Result.ok(resultData);
        } catch (Exception e) {
            log.error("批量清理记忆失败, functionType={}", functionType, e);
            return Result.fail("批量清理失败");
        }
    }

    private String getCurrentUserId() {
        return currentUserService.requireCurrentUserId().toString();
    }

    private String normalizeSessionId(String sessionId) {
        return isBlank(sessionId) ? "default" : sessionId.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String memoryKey(Long shopId, String type, String userId) {
        if (type == null) {
            return null;
        }
        switch (type.toLowerCase()) {
            case "qa":
                return keyManager.buildShopQAKey(shopId, userId);
            case "summary":
                return keyManager.buildShopSummaryKey(shopId, userId);
            case "recommend":
                return keyManager.buildShopRecommendKey(userId);
            default:
                return null;
        }
    }

    private Map<String, Object> message(String message, String key, Object value) {
        Map<String, Object> resultData = new HashMap<>();
        resultData.put("message", message);
        if (key != null) {
            resultData.put(key, value);
        }
        resultData.put("timestamp", System.currentTimeMillis());
        return resultData;
    }

    private void appendSummaryMetadata(Map<String, Object> resultData, ShopSummaryResult summary) {
        if (summary == null) {
            return;
        }
        resultData.put("traceId", summary.getTraceId());
        resultData.put("memoryId", summary.getMemoryId());
        resultData.put("promptVersion", summary.getPromptVersion());
        resultData.put("modelName", summary.getModelName());
        resultData.put("evidence", summary.getEvidence());
        resultData.put("degraded", Boolean.TRUE.equals(summary.getDegraded()));
        resultData.put("cacheHit", Boolean.TRUE.equals(summary.getCacheHit()));
        resultData.put("fallbackReason", summary.getFallbackReason());
        resultData.put("confidence", summary.getConfidence());
    }

    private String summaryMemoryKey(ShopSummaryResult summary, Long shopId, String userId) {
        if (summary != null && summary.getMemoryId() != null && !summary.getMemoryId().trim().isEmpty()) {
            return summary.getMemoryId();
        }
        return keyManager.buildShopSummaryKey(shopId, userId);
    }

    private ServerSentEvent<ShopAIStreamEvent> streamError(String message) {
        return ServerSentEvent.<ShopAIStreamEvent>builder()
                .event("error")
                .data(ShopAIStreamEvent.builder()
                        .type("error")
                        .message(message)
                        .degraded(false)
                        .build())
                .build();
    }
}
