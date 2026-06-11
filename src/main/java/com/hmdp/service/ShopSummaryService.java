package com.hmdp.service;

import com.hmdp.ai.application.ShopAIApplicationService;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.repository.RedissonChatMemoryStore;
import com.hmdp.utils.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

@Service
@Slf4j
public class ShopSummaryService {

    @Resource
    private ShopAIApplicationService shopAIApplicationService;

    @Resource
    private ShopStatsService shopStatsService;

    @Resource
    private AiResultCacheService aiResultCacheService;

    @Resource
    private LocalCacheManager localCacheManager;

    @Resource
    private RedissonChatMemoryStore chatMemoryStore;

    public LocalCacheManager getLocalCacheManager() {
        return localCacheManager;
    }

    public ShopSummaryResult generateShopSummary(Long shopId) {
        return shopAIApplicationService.summary("anonymous_user", shopId, false, "ShopSummaryService.generateShopSummary");
    }

    public ShopSummaryResult generateShopSummary(Long shopId, String userId) {
        return shopAIApplicationService.summary(userId, shopId, true, "ShopSummaryService.generateShopSummaryWithMemory");
    }

    public ShopSummaryResult generateQualitySummary(Long shopId, Integer minLiked, Integer limit, String userId) {
        log.info("高质量总结入口已收敛到编排中心, shopId={}, minLiked={}, limit={}", shopId, minLiked, limit);
        return shopAIApplicationService.summary(userId, shopId, true, "ShopSummaryService.generateQualitySummary");
    }

    public String askAboutShop(String userId, Long shopId, String question) {
        return askAboutShopDetailed(userId, "default", shopId, question).getAnswer();
    }

    public ShopAIResponse askAboutShopDetailed(String userId, String sessionId, Long shopId, String question) {
        return shopAIApplicationService.ask(userId, sessionId, shopId, question, "ShopSummaryService.askAboutShopDetailed");
    }

    public String compareShops(String userId, String sessionId, Long shopId1, Long shopId2, String aspect) {
        return compareShopsDetailed(userId, sessionId, shopId1, shopId2, aspect).getComparison();
    }

    public ShopAIResponse compareShopsDetailed(String userId, String sessionId, Long shopId1, Long shopId2, String aspect) {
        return shopAIApplicationService.compare(
                userId, sessionId, shopId1, shopId2, aspect, "ShopSummaryService.compareShopsDetailed");
    }

    public String recommendShops(String userId, String userPreference, String category, Integer limit) {
        return recommendShopsDetailed(userId, "default", userPreference, category, limit).getRecommendations();
    }

    public ShopAIResponse recommendShopsDetailed(String userId, String sessionId, String userPreference, String category, Integer limit) {
        return shopAIApplicationService.recommend(
                userId, sessionId, userPreference, category, limit, "ShopSummaryService.recommendShopsDetailed");
    }

    public void clearShopQAMemory(String userId, Long shopId) {
        shopAIApplicationService.clearShopQAMemory(userId, shopId);
    }

    public void clearRecommendMemory(String userId) {
        shopAIApplicationService.clearRecommendMemory(userId);
    }

    public void clearShopSummaryMemory(String userId, Long shopId) {
        shopAIApplicationService.clearShopSummaryMemory(userId, shopId);
    }

    public Map<String, Integer> clearAllUserMemory(String userId) {
        return shopAIApplicationService.clearAllUserMemory(userId);
    }

    public void clearShopRelatedCaches(Long shopId) {
        log.info("清除店铺{}相关缓存", shopId);
        localCacheManager.removeShopRelatedCaches(shopId);
        shopStatsService.evictShopStatsCache(shopId);
        aiResultCacheService.evictShop(shopId);
    }

    public int cleanupMemoryByFunction(String functionType) {
        return shopAIApplicationService.cleanupMemoryByFunction(functionType);
    }

    public Map<String, Object> getMemoryStatus(String userId, Long shopId) {
        String memoryKey = shopAIApplicationService.shopQAMemoryKey(shopId, userId);
        RedissonChatMemoryStore.MemoryInfo info = chatMemoryStore.getMemoryInfo(memoryKey);
        Map<String, Object> status = new java.util.HashMap<>();
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
        return shopAIApplicationService.hasMemory(memoryKey);
    }

    public int getMemoryMessageCount(String memoryKey) {
        return shopAIApplicationService.getMemoryMessageCount(memoryKey);
    }

    public long getMemoryTtl(String memoryKey) {
        return shopAIApplicationService.getMemoryTtl(memoryKey);
    }

    public Map<String, Map<String, Integer>> getMemoryStats() {
        return shopAIApplicationService.getMemoryStats();
    }

    public void refreshMemoryStatsCache() {
        localCacheManager.remove(LocalCacheManager.CacheKeys.MEMORY_STATS, LocalCacheManager.CacheType.MEMORY_STATS);
        shopAIApplicationService.getMemoryStats();
    }

    public void refreshMemoryTtl(String memoryKey) {
        shopAIApplicationService.refreshMemoryTtl(memoryKey);
    }

    public ShopSummaryResult getShopBasicInfo(Long shopId) {
        return generateShopSummary(shopId);
    }

    public boolean shopExists(Long shopId) {
        return shopStatsService.shopExists(shopId);
    }

    public void updateShopExistsCache(Long shopId, boolean exists) {
        shopStatsService.updateShopExistsCache(shopId, exists);
    }

    public int getShopReviewCount(Long shopId) {
        return shopStatsService.getShopReviewCount(shopId);
    }

    public void updateShopReviewCountCache(Long shopId, int count) {
        shopStatsService.updateShopReviewCountCache(shopId, count);
    }

    public void clearToolCallCounters() {
        shopAIApplicationService.clearToolCallCounters();
    }

    public void clearToolCallCounter(String sessionId, String toolName, Object... params) {
        shopAIApplicationService.clearToolCallCounter(sessionId, toolName, params);
    }

    public void cleanupExpiredToolCallCounters() {
        shopAIApplicationService.cleanupExpiredToolCallCounters();
    }

    public Map<String, String> getAllStats() {
        return localCacheManager.getCacheStats();
    }

    public Map<String, Long> getAllSizes() {
        return localCacheManager.getAllSizes();
    }
}
