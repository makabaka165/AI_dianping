package com.hmdp.tools;

import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.service.ShopSummaryService;
import com.hmdp.utils.LocalCacheManager;
import com.hmdp.utils.UserHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class ShopTool {

    @Autowired
    private ShopSummaryService shopSummaryService;
    
    // 注入本地缓存管理器
    @Autowired
    private LocalCacheManager localCacheManager;
    
    // 移除了对ShopAIService的直接依赖，避免循环依赖问题
    
    // 工具调用次数限制，防止无限循环调用
    private static final int MAX_TOOL_CALLS = 5;

    // ========== 店铺信息获取工具 ==========

    @Tool("获取店铺的基础总结信息，快速了解店铺概况，不保存记忆")
    public String getShopBasicSummary(@P("店铺ID，必须是数字") Long shopId) {
        try {
            log.info("🔧 Tool被调用: getShopBasicSummary, 店铺ID: {}", shopId);
            
            // 流控检查 - 基于用户ID的调用限制
            String userId = getCurrentUserId();
            if (!localCacheManager.checkAndIncrementUserCallCount(userId, "getShopBasicSummary", 10)) {
                log.warn("用户 {} 调用 getShopBasicSummary 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }
            
            // 流控检查 - 基于时间段的调用限制 (1分钟内最多3次)
            if (!localCacheManager.checkAndIncrementTimeBasedCallCount(userId, "getShopBasicSummary", 60000, 3)) {
                log.warn("用户 {} 在时间段内调用 getShopBasicSummary 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }

            ShopSummaryResult result = shopSummaryService.generateShopSummary(shopId);
            String formattedResult = formatSummaryResult(result, "基础总结");

            log.info("✅ Tool调用成功: getShopBasicSummary, 返回长度: {}", formattedResult.length());
            return formattedResult;

        } catch (Exception e) {
            log.error("❌ Tool调用失败: getShopBasicSummary, 店铺ID: {}", shopId, e);
            return "抱歉，获取店铺" + shopId + "的基础信息时出现错误，请稍后重试。";
        }
    }

    @Tool("检查店铺是否存在")
    public String checkShopExists(@P("店铺ID，必须是数字") Long shopId) {
        try {
            log.info("🔧 Tool被调用: checkShopExists, 店铺ID: {}", shopId);

            // 流控检查 - 基于用户ID的调用限制
            String userId = getCurrentUserId();
            if (!localCacheManager.checkAndIncrementUserCallCount(userId, "checkShopExists", 20)) {
                log.warn("用户 {} 调用 checkShopExists 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }
            
            // 流控检查 - 基于时间段的调用限制 (1分钟内最多5次)
            if (!localCacheManager.checkAndIncrementTimeBasedCallCount(userId, "checkShopExists", 60000, 5)) {
                log.warn("用户 {} 在时间段内调用 checkShopExists 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }

            // 添加调用次数限制，防止无限循环
            // 使用基于线程的会话标识，确保在同一线程中的调用被正确限制
            String callCounterKey = LocalCacheManager.CacheKeys.threadBasedToolCallCountKey("checkShopExists", shopId);
            Integer callCount = localCacheManager.get(callCounterKey, Integer.class, LocalCacheManager.CacheType.QUICK_CACHE);
            
            if (callCount != null && callCount > MAX_TOOL_CALLS) {
                log.warn("检测到工具调用次数过多，店铺ID: {}, 当前调用次数: {}", shopId, callCount);
                // 提供一个更友好的提示，告知用户可以尝试其他方式
                return "为避免重复处理，系统已限制该操作的重复执行。如果您需要再次确认，请稍等片刻或尝试其他相关问题。";
            }
            
            // 更新调用次数
            if (callCount == null) {
                callCount = 0;
            }
            localCacheManager.put(callCounterKey, callCount + 1, LocalCacheManager.CacheType.QUICK_CACHE);

            // 使用本地缓存避免重复调用
            String cacheKey = LocalCacheManager.CacheKeys.shopExistsKey(shopId) + "_check";
            Boolean cachedResult = localCacheManager.get(cacheKey, Boolean.class, LocalCacheManager.CacheType.QUICK_CACHE);
            if (cachedResult != null) {
                log.debug("从本地缓存获取店铺{}存在性: {}", shopId, cachedResult);
                return cachedResult ? 
                    String.format("店铺%d存在，共有%d条评论。", shopId, shopSummaryService.getShopReviewCount(shopId)) :
                    String.format("店铺%d不存在。", shopId);
            }

            // 调用已有的本地缓存功能
            boolean exists = shopSummaryService.shopExists(shopId);
            String result;
            if (exists) {
                int reviewCount = shopSummaryService.getShopReviewCount(shopId);
                result = String.format("店铺%d存在，共有%d条评论。", shopId, reviewCount);
            } else {
                result = String.format("店铺%d不存在。", shopId);
            }

            // 将结果放入本地缓存
            localCacheManager.put(cacheKey, exists, LocalCacheManager.CacheType.QUICK_CACHE);

            log.info("✅ Tool调用成功: checkShopExists, 结果: {}", result);
            return result;

        } catch (Exception e) {
            log.error("❌ Tool调用失败: checkShopExists, 店铺ID: {}", shopId, e);
            return "抱歉，检查店铺" + shopId + "的存在性时出现错误，请稍后重试。";
        }
    }

    @Tool("获取店铺的详细总结，包含记忆功能，支持后续对话")
    public String getShopDetailedSummary(@P("店铺ID，必须是数字") Long shopId) {
        try {
            log.info("🔧 Tool被调用: getShopDetailedSummary, 店铺ID: {}", shopId);
            
            // 流控检查 - 基于用户ID的调用限制
            String userId = getCurrentUserId();
            if (!localCacheManager.checkAndIncrementUserCallCount(userId, "getShopDetailedSummary", 5)) {
                log.warn("用户 {} 调用 getShopDetailedSummary 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }
            
            // 流控检查 - 基于时间段的调用限制 (1分钟内最多2次)
            if (!localCacheManager.checkAndIncrementTimeBasedCallCount(userId, "getShopDetailedSummary", 60000, 2)) {
                log.warn("用户 {} 在时间段内调用 getShopDetailedSummary 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }

            String toolUserId = "tool_user";
            ShopSummaryResult result = shopSummaryService.generateShopSummary(shopId, toolUserId);

            // 修改返回格式，避免AI Agent误识别为指令
            String formattedResult = formatSummaryResult(result, "详细分析") +
                    "\n\n现在您可以继续询问关于这家店铺的任何问题！";

            log.info("✅ Tool调用成功: getShopDetailedSummary, 返回长度: {}", formattedResult.length());
            return formattedResult;

        } catch (Exception e) {
            log.error("❌ Tool调用失败: getShopDetailedSummary, 店铺ID: {}", shopId, e);
            return "抱歉，获取店铺" + shopId + "的详细分析时出现错误，请稍后重试。";
        }
    }

    @Tool("获取店铺的高质量评价总结，基于点赞数较高的评价")
    public String getShopQualitySummary(
            @P("店铺ID，必须是数字") Long shopId,
            @P("最小点赞数要求，默认5") Integer minLiked,
            @P("最大评价数量限制，默认10") Integer limit) {
        try {
            log.info("🔧 Tool被调用: getShopQualitySummary, 店铺ID: {}", shopId);
            
            // 流控检查 - 基于用户ID的调用限制
            String userId = getCurrentUserId();
            if (!localCacheManager.checkAndIncrementUserCallCount(userId, "getShopQualitySummary", 5)) {
                log.warn("用户 {} 调用 getShopQualitySummary 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }
            
            // 流控检查 - 基于时间段的调用限制 (1分钟内最多2次)
            if (!localCacheManager.checkAndIncrementTimeBasedCallCount(userId, "getShopQualitySummary", 60000, 2)) {
                log.warn("用户 {} 在时间段内调用 getShopQualitySummary 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }

            if (minLiked == null) minLiked = 5;
            if (limit == null) limit = 10;

            String toolUserId = "tool_user";
            ShopSummaryResult result = shopSummaryService.generateQualitySummary(
                    shopId, minLiked, limit, toolUserId);

            String formattedResult = formatSummaryResult(result, "高质量评价总结") +
                    String.format("\n\n基于%d条高质量评价（点赞≥%d）", result.getTotalBlogs(), minLiked);

            log.info("✅ Tool调用成功: getShopQualitySummary, 返回长度: {}", formattedResult.length());
            return formattedResult;

        } catch (Exception e) {
            log.error("❌ Tool调用失败: getShopQualitySummary, 店铺ID: {}", shopId, e);
            return "抱歉，获取店铺" + shopId + "的高质量总结时出现错误，请稍后重试。";
        }
    }

    // ========== 智能问答工具 ==========

    @Tool("回答关于特定店铺的具体问题，基于传统算法和用户评价数据")
    public String askAboutShop(
            @P("店铺ID，必须是数字") Long shopId,
            @P("用户的具体问题") String question) {
        try {
            log.info("🔧 Tool被调用: askAboutShop, 店铺ID: {}, 问题: {}", shopId, question);
            
            // 流控检查 - 基于用户ID的调用限制
            String userId = getCurrentUserId();
            if (!localCacheManager.checkAndIncrementUserCallCount(userId, "askAboutShop", 15)) {
                log.warn("用户 {} 调用 askAboutShop 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }
            
            // 流控检查 - 基于时间段的调用限制 (1分钟内最多5次)
            if (!localCacheManager.checkAndIncrementTimeBasedCallCount(userId, "askAboutShop", 60000, 5)) {
                log.warn("用户 {} 在时间段内调用 askAboutShop 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }

            String toolUserId = "tool_user";
            String response = shopSummaryService.askAboutShop(toolUserId, shopId, question);

            String formattedResponse = String.format("【关于店铺%d的专业解答】\n\n问题：%s\n\n解答：%s\n\n分析基于真实用户评价数据", 
                    shopId, question, response);

            log.info("✅ Tool调用成功: askAboutShop, 返回长度: {}", formattedResponse.length());
            return formattedResponse;

        } catch (Exception e) {
            log.error("❌ Tool调用失败: askAboutShop, 店铺ID: {}, 问题: {}", shopId, question, e);
            return "抱歉，回答关于店铺" + shopId + "的问题时出现错误，请稍后重试。";
        }
    }

    // ========== 店铺对比工具 ==========

    @Tool("对比两个店铺的优缺点和特色")
    public String compareShops(
            @P("第一个店铺的ID") Long shopId1,
            @P("第二个店铺的ID") Long shopId2,
            @P("对比的维度，如：菜品质量、服务态度、环境装修、性价比等，可为空") String aspect,
            @P("会话ID，用于记忆，可为空") String sessionId) {
        try {
            log.info("🔧 Tool被调用: compareShops, 店铺: {} vs {}", shopId1, shopId2);
            
            // 流控检查 - 基于用户ID的调用限制
            String userId = getCurrentUserId();
            if (!localCacheManager.checkAndIncrementUserCallCount(userId, "compareShops", 3)) {
                log.warn("用户 {} 调用 compareShops 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }
            
            // 流控检查 - 基于时间段的调用限制 (1分钟内最多1次)
            if (!localCacheManager.checkAndIncrementTimeBasedCallCount(userId, "compareShops", 60000, 1)) {
                log.warn("用户 {} 在时间段内调用 compareShops 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }

            String toolUserId = "tool_user";
            if (sessionId == null || sessionId.trim().isEmpty()) {
                sessionId = "tool_compare_" + System.currentTimeMillis();
            }

            String comparison = shopSummaryService.compareShops(toolUserId, sessionId, shopId1, shopId2, aspect);

            StringBuilder result = new StringBuilder();
            result.append("【店铺对比分析】\n");
            result.append("🆚 店铺").append(shopId1).append(" VS 店铺").append(shopId2);
            if (aspect != null && !aspect.trim().isEmpty()) {
                result.append(" (").append(aspect).append("维度)");
            }
            result.append("\n\n").append(comparison);
            result.append("\n\n📊 对比基于真实用户评价数据");

            log.info("✅ Tool调用成功: compareShops, 返回长度: {}", result.length());
            return result.toString();

        } catch (Exception e) {
            log.error("❌ Tool调用失败: compareShops, 店铺: {} vs {}", shopId1, shopId2, e);
            return "抱歉，对比店铺" + shopId1 + "和店铺" + shopId2 + "时出现错误，请稍后重试。";
        }
    }

    // ========== 推荐工具 ==========

    @Tool("根据用户偏好推荐合适的店铺")
    public String recommendShops(
            @P("用户的偏好描述，如：喜欢川菜、环境要好、价格适中等") String userPreference,
            @P("店铺类型，如：餐厅、咖啡厅、奶茶店等，可为空") String category,
            @P("推荐数量，默认为3") Integer limit) {
        try {
            log.info("🔧 Tool被调用: recommendShops, 偏好: {}", userPreference);
            
            // 流控检查 - 基于用户ID的调用限制
            String userId = getCurrentUserId();
            if (!localCacheManager.checkAndIncrementUserCallCount(userId, "recommendShops", 3)) {
                log.warn("用户 {} 调用 recommendShops 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }
            
            // 流控检查 - 基于时间段的调用限制 (1分钟内最多1次)
            if (!localCacheManager.checkAndIncrementTimeBasedCallCount(userId, "recommendShops", 60000, 1)) {
                log.warn("用户 {} 在时间段内调用 recommendShops 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }

            String toolUserId = "tool_user";
            if (limit == null || limit <= 0) limit = 3;

            String recommendations = shopSummaryService.recommendShops(toolUserId, userPreference, category, limit);

            StringBuilder result = new StringBuilder();
            result.append("【个性化店铺推荐】\n");
            result.append("🎯 根据您的偏好：").append(userPreference).append("\n");
            if (category != null && !category.trim().isEmpty()) {
                result.append("🏷️ 类型筛选：").append(category).append("\n");
            }
            result.append("\n📍 为您推荐：\n\n").append(recommendations);
            result.append("\n\n💡 想了解某家店铺的详细信息？请告诉我店铺ID！");

            log.info("✅ Tool调用成功: recommendShops, 返回长度: {}", result.length());
            return result.toString();

        } catch (Exception e) {
            log.error("❌ Tool调用失败: recommendShops", e);
            return "抱歉，推荐店铺时出现错误，请稍后重试。";
        }
    }

    // ========== 记忆管理工具 ==========

    @Tool("清除特定店铺的问答记忆")
    public String clearShopQAMemory(@P("店铺ID") Long shopId) {
        try {
            log.info("🔧 Tool被调用: clearShopQAMemory, 店铺ID: {}", shopId);
            
            // 流控检查 - 基于用户ID的调用限制
            String userId = getCurrentUserId();
            if (!localCacheManager.checkAndIncrementUserCallCount(userId, "clearShopQAMemory", 10)) {
                log.warn("用户 {} 调用 clearShopQAMemory 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }

            String toolUserId = "tool_user";
            shopSummaryService.clearShopQAMemory(toolUserId, shopId);

            log.info("✅ Tool调用成功: clearShopQAMemory");
            return "✅ 已清除店铺" + shopId + "的问答记忆，可以重新开始对话。";

        } catch (Exception e) {
            log.error("❌ Tool调用失败: clearShopQAMemory, 店铺ID: {}", shopId, e);
            return "❌ 清除店铺" + shopId + "的记忆失败，请稍后重试。";
        }
    }

    @Tool("清除推荐记忆")
    public String clearRecommendMemory() {
        try {
            log.info("🔧 Tool被调用: clearRecommendMemory");
            
            // 流控检查 - 基于用户ID的调用限制
            String userId = getCurrentUserId();
            if (!localCacheManager.checkAndIncrementUserCallCount(userId, "clearRecommendMemory", 10)) {
                log.warn("用户 {} 调用 clearRecommendMemory 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }

            String toolUserId = "tool_user";
            shopSummaryService.clearRecommendMemory(toolUserId);

            log.info("✅ Tool调用成功: clearRecommendMemory");
            return "✅ 已清除推荐记忆，可以重新获取推荐。";

        } catch (Exception e) {
            log.error("❌ Tool调用失败: clearRecommendMemory", e);
            return "❌ 清除推荐记忆失败，请稍后重试。";
        }
    }

    @Tool("获取记忆使用统计信息")
    public String getMemoryStats() {
        try {
            log.info("🔧 Tool被调用: getMemoryStats");
            
            // 流控检查 - 基于用户ID的调用限制
            String userId = getCurrentUserId();
            if (!localCacheManager.checkAndIncrementUserCallCount(userId, "getMemoryStats", 10)) {
                log.warn("用户 {} 调用 getMemoryStats 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }

            Map<String, Map<String, Integer>> stats = shopSummaryService.getMemoryStats();

            StringBuilder result = new StringBuilder();
            result.append("📊 记忆使用统计：\n\n");

            for (Map.Entry<String, Map<String, Integer>> entry : stats.entrySet()) {
                result.append("🔸 ").append(entry.getKey()).append("：\n");
                for (Map.Entry<String, Integer> subEntry : entry.getValue().entrySet()) {
                    result.append("  - ").append(subEntry.getKey()).append(": ").append(subEntry.getValue()).append("\n");
                }
                result.append("\n");
            }

            log.info("✅ Tool调用成功: getMemoryStats");
            return result.toString();

        } catch (Exception e) {
            log.error("❌ Tool调用失败: getMemoryStats", e);
            return "❌ 获取记忆统计失败，请稍后重试。";
        }
    }

    // ========== 智能功能工具 ==========

    @Tool("智能选择最佳的分析方法来回答用户关于店铺的问题")
    public String smartAnalyzeShop(
            @P("店铺ID，必须是数字") Long shopId,
            @P("用户的问题或需求") String userQuery) {
        try {
            log.info("🔧 Tool被调用: smartAnalyzeShop, 店铺: {}, 查询: {}", shopId, userQuery);
            
            // 流控检查 - 基于用户ID的调用限制
            String userId = getCurrentUserId();
            if (!localCacheManager.checkAndIncrementUserCallCount(userId, "smartAnalyzeShop", 10)) {
                log.warn("用户 {} 调用 smartAnalyzeShop 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }
            
            // 流控检查 - 基于时间段的调用限制 (1分钟内最多3次)
            if (!localCacheManager.checkAndIncrementTimeBasedCallCount(userId, "smartAnalyzeShop", 60000, 3)) {
                log.warn("用户 {} 在时间段内调用 smartAnalyzeShop 次数超过限制", userId);
                return "您调用此功能的频率过高，请稍后再试。";
            }

            // 添加调用次数限制，防止无限循环
            String callCounterKey = LocalCacheManager.CacheKeys.threadBasedToolCallCountKey("smartAnalyzeShop", shopId);
            Integer callCount = localCacheManager.get(callCounterKey, Integer.class, LocalCacheManager.CacheType.QUICK_CACHE);
            
            if (callCount != null && callCount > MAX_TOOL_CALLS) {
                log.warn("检测到工具调用次数过多，店铺ID: {}, 当前调用次数: {}", shopId, callCount);
                return "为避免重复处理，系统已限制该操作的重复执行。";
            }
            
            // 更新调用次数
            if (callCount == null) {
                callCount = 0;
            }
            localCacheManager.put(callCounterKey, callCount + 1, LocalCacheManager.CacheType.QUICK_CACHE);

            // 根据查询内容智能选择分析方法
            String query = userQuery.toLowerCase();

            if (query.contains("对比") || query.contains("比较")) {
                return "💡 我注意到您想要对比分析。请告诉我另一家店铺的ID，我可以为您详细对比两家店铺。";
            } else if (query.contains("推荐") || query.contains("类似")) {
                return "💡 我注意到您想要推荐。请告诉我您的具体偏好（如菜系、环境、价位等），我可以为您推荐合适的店铺。";
            } else if (query.contains("详细") || query.contains("全面") || query.contains("深入")) {
                // 使用详细总结
                return getShopDetailedSummary(shopId);
            } else if (query.contains("高质量") || query.contains("好评") || query.contains("点赞")) {
                // 使用高质量总结
                return getShopQualitySummary(shopId, 5, 10);
            } else {
                // 使用问答功能
                return askAboutShop(shopId, userQuery);
            }

        } catch (Exception e) {
            log.error("❌ Tool调用失败: smartAnalyzeShop, 店铺: {}", shopId, e);
            return "抱歉，智能分析店铺" + shopId + "时出现错误，请稍后重试。";
        }
    }

    // 移除了queryKnowledgeBase方法，因为它依赖ShopAIService，会造成循环依赖

    // ========== 辅助方法 ==========

    /**
     * 格式化总结结果为用户友好的输出
     */
    private String formatSummaryResult(ShopSummaryResult result, String type) {
        StringBuilder formatted = new StringBuilder();
        formatted.append("【店铺").append(result.getShopId()).append(" - ").append(type).append("】\n\n");
        formatted.append("📝 ").append(result.getCoreSummary()).append("\n\n");
        formatted.append("📊 数据概览：\n");
        formatted.append("• 评价数量：").append(result.getTotalBlogs()).append("条\n");

        if (result.getAvgRating() != null) {
            formatted.append("• 平均评分：").append(String.format("%.1f", result.getAvgRating())).append("\n");
        }

        if (result.getKeyPoints() != null && !result.getKeyPoints().isEmpty()) {
            formatted.append("• 关键特点：").append(String.join("、", result.getKeyPoints())).append("\n");
        }

        if (result.getOverallSentiment() != null) {
            formatted.append("• 整体评价：").append(getSentimentDisplay(result.getOverallSentiment()));
        }

        return formatted.toString();
    }

    /**
     * 情感显示转换（使用本地缓存优化）
     */
    private String getSentimentDisplay(String sentiment) {
        if (sentiment == null) return "中性 😐";

        // 尝试从本地缓存获取结果
        String cacheKey = LocalCacheManager.CacheKeys.sentimentDisplayKey(sentiment);
        String cachedResult = localCacheManager.get(cacheKey, String.class, LocalCacheManager.CacheType.QUICK_CACHE);
        if (cachedResult != null) {
            return cachedResult;
        }

        String result;
        switch (sentiment.toLowerCase()) {
            case "positive":
                result = "正面 😊";
                break;
            case "negative":
                result = "负面 😞";
                break;
            case "neutral":
                result = "中性 😐";
                break;
            default:
                result = sentiment;
        }

        // 将结果放入本地缓存
        localCacheManager.put(cacheKey, result, LocalCacheManager.CacheType.QUICK_CACHE);

        return result;
    }
    
    /**
     * 获取当前用户ID
     * @return 用户ID，如果未登录则返回"anonymous"
     */
    private String getCurrentUserId() {
        try {
            return UserHolder.getUser() != null ? UserHolder.getUser().getId().toString() : "anonymous";
        } catch (Exception e) {
            log.warn("获取当前用户ID失败，使用匿名用户", e);
            return "anonymous";
        }
    }
}