package com.hmdp.service;

import com.hmdp.config.ChatMemoryKeyManager;
import com.hmdp.repository.RedissonChatMemoryStore;
import com.hmdp.entity.Blog;
import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.mapper.BlogMapper;
import dev.langchain4j.data.message.ChatMessage;
import com.hmdp.service.ai.ShopAIService;
import com.hmdp.utils.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ShopSummaryService {

    @Autowired
    private BlogMapper blogMapper;

    @Autowired
    private ShopAIService shopAIService; // 使用统一的AI服务

    @Autowired
    private ChatMemoryKeyManager keyManager;

    @Autowired
    private RedissonChatMemoryStore chatMemoryStore;

    @Autowired
    private RedissonClient redissonClient;

    // 注入本地缓存管理器
    @Autowired
    private LocalCacheManager localCacheManager;
    
    // 提供获取本地缓存管理器的方法
    public LocalCacheManager getLocalCacheManager() {
        return localCacheManager;
    }

    // ========== 核心业务方法 ==========

    /**
     * 生成店铺核心总结
     */
    public ShopSummaryResult generateShopSummary(Long shopId) {
        log.info("生成店铺{}总结", shopId);

        // 尝试从本地缓存获取结果
        String cacheKey = LocalCacheManager.CacheKeys.shopSummaryKey(shopId);
        ShopSummaryResult cachedResult = localCacheManager.get(cacheKey, ShopSummaryResult.class, LocalCacheManager.CacheType.SHOP_SUMMARY);
        if (cachedResult != null) {
            log.debug("从本地缓存获取店铺{}总结", shopId);
            return cachedResult;
        }

        List<Blog> blogs = blogMapper.selectBlogsByShopId(shopId);
        if (blogs.isEmpty()) {
            return createEmptyResult(shopId);
        }

        // 使用AI服务生成总结
        String summaryPrompt = buildSummaryPrompt(blogs, shopId);
        String coreSummary = shopAIService.generateSummary(summaryPrompt);

        // 情感分析和关键词提取
        String combinedContent = blogs.stream()
                .limit(10)
                .map(Blog::getContent)
                .collect(Collectors.joining("\n"));

        String sentiment = shopAIService.analyzeSentiment(combinedContent);
        String keywordsStr = shopAIService.extractKeywords(combinedContent);
        List<String> keywords = parseKeywords(keywordsStr);

        ShopSummaryResult result = ShopSummaryResult.builder()
                .shopId(shopId)
                .shopName("店铺" + shopId)
                .coreSummary(coreSummary)
                .totalBlogs(blogs.size())
                .keyPoints(keywords)
                .overallSentiment(sentiment)
                .summaryTime(LocalDateTime.now())
                .build();
        
        // 将结果放入本地缓存，缓存5分钟
        localCacheManager.put(cacheKey, result, LocalCacheManager.CacheType.SHOP_SUMMARY);
        
        return result;
    }

    /**
     * 获取高质量博客总结
     */
    public ShopSummaryResult generateQualitySummary(Long shopId, Integer minLiked, Integer limit, String userId) {
        log.info("开始生成店铺{}的高质量总结，最小点赞数: {}, 限制数量: {}, 用户: {}",
                shopId, minLiked, limit, userId);

        // 尝试从本地缓存获取结果
        String cacheKey = LocalCacheManager.CacheKeys.shopQualitySummaryKey(shopId, minLiked, limit);
        ShopSummaryResult cachedResult = localCacheManager.get(cacheKey, ShopSummaryResult.class, LocalCacheManager.CacheType.SHOP_SUMMARY);
        if (cachedResult != null) {
            log.debug("从本地缓存获取店铺{}高质量总结", shopId);
            return cachedResult;
        }

        List<Blog> qualityBlogs = blogMapper.selectQualityBlogsByShopId(shopId, minLiked, limit);
        log.info("获取到店铺{}的高质量博客数量: {}", shopId, qualityBlogs.size());

        if (qualityBlogs.isEmpty()) {
            log.warn("店铺{}无高质量博客，回退到普通总结", shopId);
            return generateShopSummary(shopId);
        }

        String prompt = buildQualitySummaryPrompt(qualityBlogs, shopId);
        String summary = shopAIService.generateSummary(prompt); // ✅ 修复：使用新的AI服务

        ShopSummaryResult result = ShopSummaryResult.builder()
                .shopId(shopId)
                .shopName("店铺" + shopId)
                .coreSummary(summary)
                .totalBlogs(qualityBlogs.size())
                .summaryTime(LocalDateTime.now())
                .build();
        
        // 将结果放入本地缓存，缓存5分钟
        localCacheManager.put(cacheKey, result, LocalCacheManager.CacheType.SHOP_SUMMARY);
        
        return result;
    }

    /**
     * 生成店铺总结（带记忆）
     */
    public ShopSummaryResult generateShopSummary(Long shopId, String userId) {
        log.info("开始生成店铺{}的带记忆总结，用户: {}", shopId, userId);

        // 直接调用基础版本
        ShopSummaryResult result = generateShopSummary(shopId);

        String memoryKey = keyManager.buildShopSummaryKey(shopId, userId);
        log.info("基础总结已生成，记忆Key: {}", memoryKey);

        return result;
    }

    /**
     * 智能店铺问答
     */
    public String askAboutShop(String userId, Long shopId, String question) {
        log.info("店铺{}问答: {}", shopId, question);

        String memoryKey = keyManager.buildShopQAKey(shopId, userId);

        List<Blog> blogs = blogMapper.selectBlogsByShopId(shopId);
        if (blogs.isEmpty()) {
            return "抱歉，店铺" + shopId + "暂无评价数据。";
        }

        String contextPrompt = buildQuestionPrompt(blogs, shopId, question);
        return shopAIService.analyzeShopData(memoryKey, contextPrompt);
    }

    /**
     * 店铺对比分析
     */
    public String compareShops(String userId, String sessionId, Long shopId1, Long shopId2, String aspect) {
        log.info("收到店铺对比请求，用户: {}, 会话: {}, 店铺: {} vs {}, 维度: {}",
                userId, sessionId, shopId1, shopId2, aspect);

        try {
            String memoryKey = keyManager.buildShopCompareKey(userId, sessionId);

            List<Blog> blogs1 = blogMapper.selectBlogsByShopId(shopId1);
            List<Blog> blogs2 = blogMapper.selectBlogsByShopId(shopId2);

            String prompt = buildComparePrompt(blogs1, blogs2, shopId1, shopId2, aspect);
            return shopAIService.analyzeShopData(memoryKey, prompt); // ✅ 修复：使用新的AI服务

        } catch (Exception e) {
            log.error("店铺对比失败，用户: {}, 会话: {}", userId, sessionId, e);
            return "对比分析失败，请稍后重试";
        }
    }

    /**
     * 基于偏好的推荐
     */
    public String recommendShops(String userId, String userPreference, String category, Integer limit) {
        log.info("收到推荐请求，用户: {}, 偏好: {}, 类型: {}, 限制数量: {}",
                userId, userPreference, category, limit);

        try {
            String memoryKey = keyManager.buildShopRecommendKey(userId);

            String prompt = String.format(
                    "用户偏好：%s，类型：%s，请基于这些偏好推荐%d家店铺。",
                    userPreference, category != null ? category : "不限", limit
            );

            return shopAIService.analyzeShopData(memoryKey, prompt); // ✅ 修复：使用新的AI服务

        } catch (Exception e) {
            log.error("推荐失败，用户: {}", userId, e);
            return "推荐失败，请稍后重试";
        }
    }

    // ========== 记忆管理功能（完全保留） ==========

    public void clearShopQAMemory(String userId, Long shopId) {
        String memoryKey = keyManager.buildShopQAKey(shopId, userId);
        log.info("清除店铺{}问答记忆: {}", shopId, memoryKey);

        try {
            chatMemoryStore.deleteMessages(memoryKey);
            log.info("已清除店铺{}问答记忆", shopId);
        } catch (Exception e) {
            log.error("清除问答记忆失败", e);
            throw new RuntimeException("清除记忆失败", e);
        }
    }

    public void clearRecommendMemory(String userId) {
        String memoryKey = keyManager.buildShopRecommendKey(userId);
        log.info("准备清除用户{}的推荐记忆，Key: {}", userId, memoryKey);

        try {
            chatMemoryStore.deleteMessages(memoryKey);
            log.info("已清除用户{}的推荐记忆", userId);
        } catch (Exception e) {
            log.error("清除推荐记忆失败，用户: {}", userId, e);
            throw new RuntimeException("清除记忆失败", e);
        }
    }

    public void clearShopSummaryMemory(String userId, Long shopId) {
        String memoryKey = keyManager.buildShopSummaryKey(shopId, userId);
        log.info("准备清除用户{}对店铺{}的总结记忆，Key: {}", userId, shopId, memoryKey);

        try {
            chatMemoryStore.deleteMessages(memoryKey);
            log.info("已清除用户{}对店铺{}的总结记忆", userId, shopId);
        } catch (Exception e) {
            log.error("清除总结记忆失败，用户: {}, 店铺: {}", userId, shopId, e);
            throw new RuntimeException("清除记忆失败", e);
        }
    }

    public Map<String, Integer> clearAllUserMemory(String userId) {
        log.info("开始清除用户{}的所有记忆", userId);

        Map<String, Integer> result = new HashMap<>();
        String appName = "hmdp";

        try {
            String[] patterns = {
                    appName + ":memory:" + ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX + ":*:" + userId,
                    appName + ":memory:" + ChatMemoryKeyManager.SHOP_QA_PREFIX + ":*:" + userId,
                    appName + ":memory:" + ChatMemoryKeyManager.SHOP_COMPARE_PREFIX + ":" + userId + ":*",
                    appName + ":memory:" + ChatMemoryKeyManager.SHOP_RECOMMEND_PREFIX + ":" + userId,
                    appName + ":memory:" + ChatMemoryKeyManager.AI_CHAT_PREFIX + ":" + userId + ":*"
            };

            int totalDeleted = 0;
            for (String pattern : patterns) {
                Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(pattern);
                int count = 0;
                for (String key : keys) {
                    if (redissonClient.getBucket(key).delete()) {
                        count++;
                    }
                }
                result.put(pattern, count);
                totalDeleted += count;
                log.debug("清除模式 {} 的记忆: {}条", pattern, count);
            }

            log.info("成功清除用户{}的所有记忆，总计: {}条", userId, totalDeleted);
            result.put("total", totalDeleted);

        } catch (Exception e) {
            log.error("清除用户所有记忆失败，用户: {}", userId, e);
            throw new RuntimeException("清除所有记忆失败", e);
        }

        return result;
    }

    public int cleanupMemoryByFunction(String functionType) {
        log.info("开始清理功能类型 {} 的所有记忆", functionType);

        try {
            int count = chatMemoryStore.deleteMessagesByFunction(functionType);
            log.info("成功清理功能类型 {} 的记忆，总计: {}条", functionType, count);
            return count;
        } catch (Exception e) {
            log.error("清理功能记忆失败，功能类型: {}", functionType, e);
            throw new RuntimeException("清理功能记忆失败", e);
        }
    }

    public Map<String, Object> getMemoryStatus(String userId, Long shopId) {
        String memoryKey = keyManager.buildShopQAKey(shopId, userId);

        RedissonChatMemoryStore.MemoryInfo info = chatMemoryStore.getMemoryInfo(memoryKey);

        Map<String, Object> status = new HashMap<>();
        status.put("memoryKey", info.getMemoryKey());
        status.put("exists", info.isExists());
        status.put("messageCount", info.getMessageCount());
        status.put("ttlSeconds", info.getTtlSeconds());
        status.put("ttlMinutes", info.getTtlSeconds() / 60);
        status.put("functionType", info.getFunctionType());

        return status;
    }

    public Map<String, RedissonChatMemoryStore.MemoryStats> getAllMemoryStats() {
        return chatMemoryStore.getAllMemoryStatistics();
    }

    public boolean hasMemory(String memoryKey) {
        log.debug("检查记忆Key {} 是否存在", memoryKey);

        try {
            return chatMemoryStore.exists(memoryKey);
        } catch (Exception e) {
            log.error("检查记忆存在性失败，Key: {}", memoryKey, e);
            return false;
        }
    }

    public int getMemoryMessageCount(String memoryKey) {
        log.info("查询记忆Key {} 的消息数量", memoryKey);

        try {
            List<ChatMessage> messages = chatMemoryStore.getMessages(memoryKey);
            int count = messages != null ? messages.size() : 0;
            log.info("记忆Key {} 当前有 {} 条消息", memoryKey, count);
            return count;
        } catch (Exception e) {
            log.error("查询记忆消息数量失败，Key: {}", memoryKey, e);
            return 0;
        }
    }

    public long getMemoryTtl(String memoryKey) {
        log.debug("获取记忆Key {} 的剩余时间", memoryKey);

        try {
            return chatMemoryStore.getTimeToLive(memoryKey);
        } catch (Exception e) {
            log.error("获取记忆TTL失败，Key: {}", memoryKey, e);
            return -1;
        }
    }

    public Map<String, Map<String, Integer>> getMemoryStats() {
        log.info("获取记忆统计信息");
        
        // 尝试从本地缓存获取统计信息
        String cacheKey = LocalCacheManager.CacheKeys.MEMORY_STATS;
        Map<String, Map<String, Integer>> cachedStats = localCacheManager.get(cacheKey, Map.class, LocalCacheManager.CacheType.MEMORY_STATS);
        if (cachedStats != null) {
            log.debug("从本地缓存获取记忆统计信息");
            return cachedStats;
        }
        
        try {
            Map<String, Map<String, Integer>> result = new HashMap<>();
            
            // 获取所有内存统计信息
            Map<String, RedissonChatMemoryStore.MemoryStats> allStats = getAllMemoryStats();
            
            // 转换为需要的格式
            Map<String, Integer> statsSummary = new HashMap<>();
            int totalMessages = 0;
            int totalMemories = allStats.size();
            
            for (Map.Entry<String, RedissonChatMemoryStore.MemoryStats> entry : allStats.entrySet()) {
                totalMessages += entry.getValue().getTotalMessages();
            }
            
            statsSummary.put("总记忆数量", totalMemories);
            statsSummary.put("总消息数量", totalMessages);
            
            result.put("统计概览", statsSummary);
            
            log.info("成功获取记忆统计信息，总记忆数: {}, 总消息数: {}", totalMemories, totalMessages);
            
            // 将结果放入本地缓存，统计信息缓存1分钟（因为可能变化较快）
            localCacheManager.put(cacheKey, result, LocalCacheManager.CacheType.MEMORY_STATS);
            
            return result;
            
        } catch (Exception e) {
            log.error("获取记忆统计信息失败", e);
            throw new RuntimeException("获取记忆统计信息失败", e);
        }
    }

    /**
     * 主动更新记忆统计信息缓存
     */
    public void refreshMemoryStatsCache() {
        log.info("主动更新记忆统计信息缓存");
        try {
            String cacheKey = LocalCacheManager.CacheKeys.MEMORY_STATS;
            Map<String, Map<String, Integer>> result = new HashMap<>();
            
            // 获取所有内存统计信息
            Map<String, RedissonChatMemoryStore.MemoryStats> allStats = getAllMemoryStats();
            
            // 转换为需要的格式
            Map<String, Integer> statsSummary = new HashMap<>();
            int totalMessages = 0;
            int totalMemories = allStats.size();
            
            for (Map.Entry<String, RedissonChatMemoryStore.MemoryStats> entry : allStats.entrySet()) {
                totalMessages += entry.getValue().getTotalMessages();
            }
            
            statsSummary.put("总记忆数量", totalMemories);
            statsSummary.put("总消息数量", totalMessages);
            
            result.put("统计概览", statsSummary);
            
            // 更新本地缓存
            localCacheManager.put(cacheKey, result, LocalCacheManager.CacheType.MEMORY_STATS);
            
            log.info("成功更新记忆统计信息缓存，总记忆数: {}, 总消息数: {}", totalMemories, totalMessages);
        } catch (Exception e) {
            log.error("更新记忆统计信息缓存失败", e);
        }
    }

    public void refreshMemoryTtl(String memoryKey) {
        log.info("刷新记忆Key {} 的过期时间", memoryKey);

        try {
            chatMemoryStore.refreshTtl(memoryKey);
            log.info("成功刷新记忆Key {} 的过期时间", memoryKey);
        } catch (Exception e) {
            log.error("刷新记忆TTL失败，Key: {}", memoryKey, e);
            throw new RuntimeException("刷新记忆TTL失败", e);
        }
    }

    // ========== Tool支持方法（完全保留） ==========

    public ShopSummaryResult getShopBasicInfo(Long shopId) {
        log.info("Tool专用: 获取店铺{}基础信息", shopId);
        return generateShopSummary(shopId);
    }

    public boolean shopExists(Long shopId) {
        // 尝试从本地缓存获取结果
        String cacheKey = LocalCacheManager.CacheKeys.shopExistsKey(shopId);
        Boolean cachedResult = localCacheManager.get(cacheKey, Boolean.class, LocalCacheManager.CacheType.SHOP_INFO);
        if (cachedResult != null) {
            log.debug("从本地缓存获取店铺{}存在性: {}", shopId, cachedResult);
            return cachedResult;
        }
        
        try {
            List<Blog> blogs = blogMapper.selectBlogsByShopId(shopId);
            boolean exists = blogs != null && !blogs.isEmpty();
            log.info("店铺{}存在性检查: {}", shopId, exists ? "存在" : "不存在");
            
            // 将结果放入本地缓存，缓存1分钟
            localCacheManager.put(cacheKey, exists, LocalCacheManager.CacheType.SHOP_INFO);
            
            return exists;
        } catch (Exception e) {
            log.error("检查店铺{}存在性失败", shopId, e);
            return false;
        }
    }

    /**
     * 主动更新店铺存在性缓存
     * @param shopId 店铺ID
     * @param exists 存在性状态
     */
    public void updateShopExistsCache(Long shopId, boolean exists) {
        String cacheKey = LocalCacheManager.CacheKeys.shopExistsKey(shopId);
        localCacheManager.put(cacheKey, exists, LocalCacheManager.CacheType.SHOP_INFO);
        log.info("更新店铺{}存在性缓存为: {}", shopId, exists);
    }

    public int getShopReviewCount(Long shopId) {
        // 尝试从本地缓存获取结果
        String cacheKey = LocalCacheManager.CacheKeys.shopReviewCountKey(shopId);
        Integer cachedCount = localCacheManager.get(cacheKey, Integer.class, LocalCacheManager.CacheType.SHOP_STATS);
        if (cachedCount != null) {
            log.debug("从本地缓存获取店铺{}评价数量: {}", shopId, cachedCount);
            return cachedCount;
        }
        
        try {
            List<Blog> blogs = blogMapper.selectBlogsByShopId(shopId);
            int count = blogs != null ? blogs.size() : 0;
            log.info("店铺{}评价数量: {}", shopId, count);
            
            // 将结果放入本地缓存，缓存1分钟
            localCacheManager.put(cacheKey, count, LocalCacheManager.CacheType.SHOP_STATS);
            
            return count;
        } catch (Exception e) {
            log.error("获取店铺{}评价数量失败", shopId, e);
            return 0;
        }
    }

    /**
     * 主动更新店铺评价数量缓存
     * @param shopId 店铺ID
     * @param count 评价数量
     */
    public void updateShopReviewCountCache(Long shopId, int count) {
        String cacheKey = LocalCacheManager.CacheKeys.shopReviewCountKey(shopId);
        localCacheManager.put(cacheKey, count, LocalCacheManager.CacheType.SHOP_STATS);
        log.info("更新店铺{}评价数量缓存为: {}", shopId, count);
    }

    // ========== 工具方法 ==========

    private ShopSummaryResult createEmptyResult(Long shopId) {
        return ShopSummaryResult.builder()
                .shopId(shopId)
                .coreSummary("暂无评价数据")
                .totalBlogs(0)
                .summaryTime(LocalDateTime.now())
                .build();
    }

    private String buildSummaryPrompt(List<Blog> blogs, Long shopId) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析店铺").append(shopId).append("的用户评价并生成总结：\n\n");

        for (int i = 0; i < Math.min(10, blogs.size()); i++) {
            prompt.append("- ").append(blogs.get(i).getContent()).append("\n");
        }

        prompt.append("\n请生成专业、客观的总结。");
        return prompt.toString();
    }

    private String buildQualitySummaryPrompt(List<Blog> blogs, Long shopId) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析店铺").append(shopId).append("的高质量用户评价并生成总结：\n\n");

        for (Blog blog : blogs) {
            prompt.append("- ").append(blog.getContent()).append(" (点赞: ").append(blog.getLiked()).append(")\n");
        }

        prompt.append("\n请基于这些高质量评价生成专业总结。");
        return prompt.toString();
    }

    private String buildQuestionPrompt(List<Blog> blogs, Long shopId, String question) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户问题：").append(question).append("\n\n");
        prompt.append("店铺").append(shopId).append("的评价数据：\n");

        for (int i = 0; i < Math.min(5, blogs.size()); i++) {
            prompt.append("- ").append(blogs.get(i).getContent()).append("\n");
        }

        prompt.append("\n请基于以上评价专业地回答用户问题。");
        return prompt.toString();
    }

    private String buildComparePrompt(List<Blog> blogs1, List<Blog> blogs2, Long shopId1, Long shopId2, String aspect) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请对比分析以下两个店铺");
        if (aspect != null && !aspect.isEmpty()) {
            prompt.append("在").append(aspect).append("方面");
        }
        prompt.append("的表现：\n\n");

        prompt.append("店铺").append(shopId1).append("的评价：\n");
        for (int i = 0; i < Math.min(5, blogs1.size()); i++) {
            prompt.append("- ").append(blogs1.get(i).getContent()).append("\n");
        }

        prompt.append("\n店铺").append(shopId2).append("的评价：\n");
        for (int i = 0; i < Math.min(5, blogs2.size()); i++) {
            prompt.append("- ").append(blogs2.get(i).getContent()).append("\n");
        }

        prompt.append("\n请基于以上评价进行详细对比分析。");
        return prompt.toString();
    }

    private List<String> parseKeywords(String keywordsStr) {
        return Arrays.stream(keywordsStr.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(5)
                .collect(Collectors.toList());
    }

    /**
     * 清理工具调用计数器缓存
     */
    public void clearToolCallCounters() {
        localCacheManager.clearToolCallCounters();
        log.info("已清理所有工具调用计数器");
    }
    
    /**
     * 清理特定工具调用计数器
     */
    public void clearToolCallCounter(String sessionId, String toolName, Object... params) {
        localCacheManager.clearToolCallCounter(sessionId, toolName, params);
        log.debug("已清理工具调用计数器: {}", toolName);
    }
    
    /**
     * 清理过期的工具调用计数器
     */
    public void cleanupExpiredToolCallCounters() {
        localCacheManager.cleanupExpiredToolCallCounters();
        log.info("已清理过期的工具调用计数器");
    }
    
    // 获取所有缓存统计
    public Map<String, String> getAllStats() {
        return localCacheManager.getCacheStats();
    }
    
    // 获取所有缓存大小
    public Map<String, Long> getAllSizes() {
        return localCacheManager.getAllSizes();
    }
}