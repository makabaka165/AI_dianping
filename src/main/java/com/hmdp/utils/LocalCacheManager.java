package com.hmdp.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地缓存管理器
 * 使用Caffeine实现，提供基本的缓存操作功能
 * 默认配置：最大1000条记录，写入后5分钟过期
 */
@Component
@Slf4j
public class LocalCacheManager {

    // 不同业务场景的缓存实例
    private Cache<String, Object> shopInfoCache;      // 店铺基础信息：30分钟
    private Cache<String, Object> shopStatsCache;     // 店铺统计信息：5分钟
    private Cache<String, Object> memoryStatsCache;   // 记忆统计：2分钟
    private Cache<String, Object> aiResultCache;      // AI分析结果：1小时
    private Cache<String, Object> quickCache;         // 快速缓存：1分钟
    private Cache<String, AtomicInteger> rateLimitCache;

    // 店铺相关的缓存键映射，用于快速清理
    private Map<Long, Set<String>> shopRelatedCacheKeys = new ConcurrentHashMap<>();

    // 缓存类型枚举
    public enum CacheType {
        SHOP_INFO,           // 店铺基础信息
        SHOP_STATS,          // 店铺统计信息
        MEMORY_STATS,        // 记忆统计
        AI_RESULT,           // AI分析结果
        QUICK_CACHE          // 快速缓存
    }
    
    // 缓存键常量
    public static class CacheKeys {
        public static final String SHOP_EXISTS = "shop_exists_";
        public static final String SHOP_REVIEW_COUNT = "shop_review_count_";
        public static final String MEMORY_STATS = "memory_stats";
        public static final String SHOP_SUMMARY = "shop_summary_";
        public static final String SHOP_QUALITY_SUMMARY = "shop_quality_summary_";

        public static String shopExistsKey(Long shopId) {
            return SHOP_EXISTS + shopId;
        }

        public static String shopReviewCountKey(Long shopId) {
            return SHOP_REVIEW_COUNT + shopId;
        }

        /**
         * 生成店铺总结缓存键
         */
        public static String shopSummaryKey(Long shopId) {
            return SHOP_SUMMARY + shopId;
        }
        
        /**
         * 生成店铺高质量总结缓存键
         */
        public static String shopQualitySummaryKey(Long shopId, Integer minLiked, Integer limit) {
            return SHOP_QUALITY_SUMMARY + shopId + "_" + minLiked + "_" + limit;
        }
    }

    @PostConstruct
    public void init() {
        // 店铺基础信息缓存 - 30分钟过期
        shopInfoCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .recordStats()
                .build();

        // 店铺统计信息缓存 - 5分钟过期
        shopStatsCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats()
                .build();

        // 记忆统计缓存 - 2分钟过期
        memoryStatsCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(2, TimeUnit.MINUTES)
                .recordStats()
                .build();

        // AI分析结果缓存 - 1小时过期
        aiResultCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .recordStats()
                .build();

        // 快速缓存 - 1分钟过期
        quickCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .recordStats()
                .build();

        // Single-instance local rate limit cache. The window segment is part of
        // each key, so callers recover automatically when the window advances.
        rateLimitCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterAccess(2, TimeUnit.HOURS)
                .recordStats()
                .build();

        log.info("LocalCacheManager 初始化完成");
    }


    // 根据缓存类型获取对应的缓存实例
    private Cache<String, Object> getCacheByType(CacheType cacheType) {
        switch (cacheType) {
            case SHOP_INFO:
                return shopInfoCache;
            case SHOP_STATS:
                return shopStatsCache;
            case MEMORY_STATS:
                return memoryStatsCache;
            case AI_RESULT:
                return aiResultCache;
            case QUICK_CACHE:
                return quickCache;
            default:
                throw new IllegalArgumentException("未知的缓存类型: " + cacheType);
        }
    }


    /**
     * 获取缓存中的值
     * @param key 缓存键
     * @param clazz 返回值类型
     * @param <T> 泛型类型
     * @return 缓存值，如果不存在或类型不匹配则返回null
     */
    public <T> T get(String key, Class<T> clazz, CacheType cacheType) {
        try {
            Cache<String, Object> cache = getCacheByType(cacheType);
            Object value = cache.getIfPresent(key);
            if (value != null && clazz.isInstance(value)) {
                return clazz.cast(value);
            }
            return null;
        } catch (Exception e) {
            log.error("获取缓存失败，key: {}", key, e);
            return null;
        }
    }

    /**
     * 将值放入缓存（使用默认过期时间）
     * @param key 缓存键
     * @param value 缓存值
     */
    public void put(String key, Object value, CacheType cacheType) {
        try {
            Cache<String, Object> cache = getCacheByType(cacheType);
            cache.put(key, value);
            log.debug("缓存已更新: {}", key);
            
            // 如果是店铺相关缓存，记录到映射中
            if (cacheType == CacheType.SHOP_INFO || cacheType == CacheType.SHOP_STATS || cacheType == CacheType.AI_RESULT) {
                if (key.contains("shop_")) {
                    // 提取店铺ID
                    Long shopId = extractShopIdFromKey(key);
                    if (shopId != null) {
                        shopRelatedCacheKeys.computeIfAbsent(shopId, k -> ConcurrentHashMap.newKeySet()).add(key);
                    }
                }
            }
        } catch (Exception e) {
            log.error("设置缓存失败，key: {}", key, e);
        }
    }

    /**
     * 从缓存中移除指定键的值
     * @param key 缓存键
     * @param cacheType 缓存类型
     */
    public void remove(String key, CacheType cacheType) {
        try {
            Cache<String, Object> cache = getCacheByType(cacheType);
            cache.invalidate(key);
            log.debug("缓存已移除: {}", key);
            
            // 从映射中移除
            if (cacheType == CacheType.SHOP_INFO || cacheType == CacheType.SHOP_STATS || cacheType == CacheType.AI_RESULT) {
                Long shopId = extractShopIdFromKey(key);
                if (shopId != null) {
                    Set<String> keys = shopRelatedCacheKeys.get(shopId);
                    if (keys != null) {
                        keys.remove(key);
                    }
                }
            }
        } catch (Exception e) {
            log.error("移除缓存失败，key: {}", key, e);
        }
    }

    /**
     * 批量清理店铺相关缓存
     * @param shopId 店铺ID
     */
    public void removeShopRelatedCaches(Long shopId) {
        Set<String> keys = shopRelatedCacheKeys.get(shopId);
        if (keys != null) {
            for (String key : keys) {
                // 根据key判断缓存类型并清理
                if (key.startsWith(CacheKeys.SHOP_EXISTS)) {
                    remove(key, CacheType.SHOP_INFO);
                } else if (key.startsWith(CacheKeys.SHOP_REVIEW_COUNT)) {
                    remove(key, CacheType.SHOP_STATS);
                } else if (key.startsWith(CacheKeys.SHOP_SUMMARY)) {
                    remove(key, CacheType.AI_RESULT);
                } else if (key.startsWith(CacheKeys.SHOP_QUALITY_SUMMARY)) {
                    remove(key, CacheType.AI_RESULT);
                }
            }
            shopRelatedCacheKeys.remove(shopId);
        }
    }

    /**
     * 从缓存键中提取店铺ID
     * @param key 缓存键
     * @return 店铺ID，如果无法提取则返回null
     */
    private Long extractShopIdFromKey(String key) {
        try {
            // 从shop_exists_123这样的键中提取123
            if (key.contains("shop_") && key.contains("_")) {
                String[] parts = key.split("_");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0 && parts[i - 1].equals("shop") && i < parts.length) {
                        try {
                            return Long.parseLong(parts[i]);
                        } catch (NumberFormatException e) {
                            // 尝试下一个部分
                        }
                    }
                    // 尝试直接解析数字
                    try {
                        return Long.parseLong(parts[i]);
                    } catch (NumberFormatException e) {
                        // 继续尝试下一个
                    }
                }
            }
            // 如果上面的方法不行，尝试从shop_summary_123中提取
            if (key.contains("shop_summary_")) {
                String[] parts = key.split("shop_summary_");
                if (parts.length > 1) {
                    String idPart = parts[1];
                    // 提取数字部分
                    return parseLeadingLong(idPart);
                }
            }
            if (key.contains("shop_quality_summary_")) {
                String[] parts = key.split("shop_quality_summary_");
                if (parts.length > 1) {
                    String idPart = parts[1];
                    // 提取数字部分
                    return parseLeadingLong(idPart);
                }
            }
        } catch (Exception e) {
            log.warn("无法从缓存键中提取店铺ID: {}", key, e);
        }
        return null;
    }

    private Long parseLeadingLong(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isDigit(ch)) {
                break;
            }
            digits.append(ch);
        }
        return digits.length() == 0 ? null : Long.parseLong(digits.toString());
    }

    /**
     * 获取缓存统计信息
     * @param cacheType 缓存类型
     * @return 统计信息字符串
     */
    public String getStats(CacheType cacheType) {
        return getCacheByType(cacheType).stats().toString();
    }

    /**
     * 获取缓存大小
     * @param cacheType 缓存类型
     * @return 当前缓存中的条目数量
     */
    public long size(CacheType cacheType) {
        return getCacheByType(cacheType).estimatedSize();
    }


    // 便捷方法
    public <T> T getShopInfo(String key, Class<T> clazz) {
        return get(key, clazz, CacheType.SHOP_INFO);
    }

    public void putShopInfo(String key, Object value) {
        put(key, value, CacheType.SHOP_INFO);
    }

    public <T> T getShopStats(String key, Class<T> clazz) {
        return get(key, clazz, CacheType.SHOP_STATS);
    }

    public void putShopStats(String key, Object value) {
        put(key, value, CacheType.SHOP_STATS);
    }

    public <T> T getAIResult(String key, Class<T> clazz) {
        return get(key, clazz, CacheType.AI_RESULT);
    }

    public void putAIResult(String key, Object value) {
        put(key, value, CacheType.AI_RESULT);
    }

    // 清空特定类型的缓存
    public void invalidateCache(CacheType type) {
        getCacheByType(type).invalidateAll();
        log.info("已清空缓存类型: {}", type);
    }

    // 清空所有缓存
    public void invalidateAll() {
        shopInfoCache.invalidateAll();
        shopStatsCache.invalidateAll();
        memoryStatsCache.invalidateAll();
        aiResultCache.invalidateAll();
        quickCache.invalidateAll();
        rateLimitCache.invalidateAll();
        shopRelatedCacheKeys.clear();
        log.info("已清空所有缓存");
    }

    public boolean checkAndIncrementUserCallCount(String userId, String toolName, int limit, long windowMillis) {
        return checkAndIncrementUserCallCount(userId, toolName, limit, windowMillis, System.currentTimeMillis());
    }

    boolean checkAndIncrementUserCallCount(String userId, String toolName, int limit, long windowMillis, long nowMillis) {
        if (limit <= 0) {
            return false;
        }
        long safeWindowMillis = Math.max(1L, windowMillis);
        long segment = Math.floorDiv(Math.max(0L, nowMillis), safeWindowMillis);
        String key = safeRateLimitSegment(userId, "anonymous")
                + ":" + safeRateLimitSegment(toolName, "default")
                + ":" + segment;
        AtomicInteger counter = rateLimitCache.get(key, ignored -> new AtomicInteger(0));
        return counter.incrementAndGet() <= limit;
    }

    public void cleanupExpiredUserCallCounters() {
        rateLimitCache.cleanUp();
    }

    private String safeRateLimitSegment(String value, String fallback) {
        String text = value == null || value.trim().isEmpty() ? fallback : value.trim();
        return text.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    /**
     * 获取缓存统计信息
     */
    public Map<String, String> getCacheStats() {
        Map<String, String> stats = new HashMap<>();
        stats.put("shopInfo", shopInfoCache.stats().toString());
        stats.put("shopStats", shopStatsCache.stats().toString());
        stats.put("memoryStats", memoryStatsCache.stats().toString());
        stats.put("aiResult", aiResultCache.stats().toString());
        stats.put("quick", quickCache.stats().toString());
        return Collections.unmodifiableMap(stats);
    }
    
    /**
     * 获取详细的缓存统计信息
     */
    public Map<String, Map<String, Object>> getDetailedCacheStats() {
        Map<String, Map<String, Object>> detailedStats = new HashMap<>();
        
        detailedStats.put("shopInfo", getCacheDetailStats(shopInfoCache));
        detailedStats.put("shopStats", getCacheDetailStats(shopStatsCache));
        detailedStats.put("memoryStats", getCacheDetailStats(memoryStatsCache));
        detailedStats.put("aiResult", getCacheDetailStats(aiResultCache));
        detailedStats.put("quick", getCacheDetailStats(quickCache));
        
        return Collections.unmodifiableMap(detailedStats);
    }
    
    private Map<String, Object> getCacheDetailStats(Cache<String, Object> cache) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("estimatedSize", cache.estimatedSize());
        stats.put("stats", cache.stats().toString());
        stats.put("hitRate", cache.stats().hitRate());
        stats.put("missRate", cache.stats().missRate());
        stats.put("requestCount", cache.stats().requestCount());
        return stats;
    }
    
    // 获取所有缓存的大小
    public Map<String, Long> getAllSizes() {
        Map<String, Long> sizes = new HashMap<>();
        sizes.put("shopInfo", shopInfoCache.estimatedSize());
        sizes.put("shopStats", shopStatsCache.estimatedSize());
        sizes.put("memoryStats", memoryStatsCache.estimatedSize());
        sizes.put("aiResult", aiResultCache.estimatedSize());
        sizes.put("quick", quickCache.estimatedSize());
        return Collections.unmodifiableMap(sizes);
    }

    /**
     * 缓存自动调优建议
     * 基于缓存统计信息提供调优建议
     */
    public Map<String, String> getCacheTuningRecommendations() {
        Map<String, String> recommendations = new HashMap<>();
        
        // 检查各个缓存实例的统计信息
        checkCacheTuning("shopInfo", shopInfoCache, recommendations);
        checkCacheTuning("shopStats", shopStatsCache, recommendations);
        checkCacheTuning("memoryStats", memoryStatsCache, recommendations);
        checkCacheTuning("aiResult", aiResultCache, recommendations);
        checkCacheTuning("quick", quickCache, recommendations);
        
        return recommendations;
    }
    
    private void checkCacheTuning(String cacheName, Cache<String, Object> cache, Map<String, String> recommendations) {
        var stats = cache.stats();
        long estimatedSize = cache.estimatedSize();
        
        // 命中率检查
        if (stats.hitRate() < 0.7) {
            recommendations.put(cacheName + ".hitRate", 
                String.format("命中率较低: %.2f%%，考虑增加缓存大小或优化缓存键", stats.hitRate() * 100));
        }
        
        // 缓存使用率检查
        if (stats.requestCount() > 1000) {
            if (stats.hitRate() > 0.9) {
                recommendations.put(cacheName + ".usage", 
                    String.format("高价值缓存，命中率: %.2f%%", stats.hitRate() * 100));
            }
        }
        
        // 缓存大小效率检查
        if (stats.requestCount() > 100 && stats.hitRate() < 0.1) {
            recommendations.put(cacheName + ".efficiency", 
                "缓存效率较低，考虑减少缓存大小以节省内存");
        }
    }
    
    /**
     * 获取缓存健康度报告
     */
    public Map<String, Object> getCacheHealthReport() {
        Map<String, Object> report = new HashMap<>();
        
        Map<String, Object> shopInfoReport = createCacheReport("shopInfo", shopInfoCache);
        Map<String, Object> shopStatsReport = createCacheReport("shopStats", shopStatsCache);
        Map<String, Object> memoryStatsReport = createCacheReport("memoryStats", memoryStatsCache);
        Map<String, Object> aiResultReport = createCacheReport("aiResult", aiResultCache);
        Map<String, Object> quickReport = createCacheReport("quick", quickCache);
        
        report.put("shopInfo", shopInfoReport);
        report.put("shopStats", shopStatsReport);
        report.put("memoryStats", memoryStatsReport);
        report.put("aiResult", aiResultReport);
        report.put("quick", quickReport);
        
        return report;
    }
    
    private Map<String, Object> createCacheReport(String cacheName, Cache<String, Object> cache) {
        Map<String, Object> report = new HashMap<>();
        var stats = cache.stats();
        
        report.put("estimatedSize", cache.estimatedSize());
        report.put("hitRate", String.format("%.2f%%", stats.hitRate() * 100));
        report.put("missRate", String.format("%.2f%%", stats.missRate() * 100));
        report.put("requestCount", stats.requestCount());
        report.put("hitCount", stats.hitCount());
        report.put("missCount", stats.missCount());
        
        return report;
    }
    
    /**
     * 获取详细的缓存分析报告
     * 包括性能指标、优化建议等
     */
    public Map<String, Map<String, Object>> getDetailedCacheAnalysis() {
        Map<String, Map<String, Object>> analysis = new HashMap<>();
        
        analysis.put("shopInfo", analyzeCache("shopInfo", shopInfoCache));
        analysis.put("shopStats", analyzeCache("shopStats", shopStatsCache));
        analysis.put("memoryStats", analyzeCache("memoryStats", memoryStatsCache));
        analysis.put("aiResult", analyzeCache("aiResult", aiResultCache));
        analysis.put("quick", analyzeCache("quick", quickCache));
        
        return analysis;
    }
    
    private Map<String, Object> analyzeCache(String cacheName, Cache<String, Object> cache) {
        Map<String, Object> analysis = new HashMap<>();
        var stats = cache.stats();
        long estimatedSize = cache.estimatedSize();
        
        // 基础统计信息
        analysis.put("estimatedSize", estimatedSize);
        analysis.put("hitRate", stats.hitRate());
        analysis.put("missRate", stats.missRate());
        analysis.put("requestCount", stats.requestCount());
        analysis.put("hitCount", stats.hitCount());
        analysis.put("missCount", stats.missCount());
        
        // 性能评估
        String performance = evaluatePerformance(stats.hitRate());
        analysis.put("performance", performance);
        
        // 优化建议
        List<String> recommendations = generateRecommendations(cacheName, stats, estimatedSize);
        analysis.put("recommendations", recommendations);
        
        // 缓存健康度 (0-100分)
        int healthScore = calculateHealthScore(stats, estimatedSize);
        analysis.put("healthScore", healthScore);
        
        return analysis;
    }
    
    private String evaluatePerformance(double hitRate) {
        if (hitRate >= 0.9) {
            return "优秀";
        } else if (hitRate >= 0.8) {
            return "良好";
        } else if (hitRate >= 0.7) {
            return "一般";
        } else {
            return "较差";
        }
    }
    
    private List<String> generateRecommendations(String cacheName, CacheStats stats, long estimatedSize) {
        List<String> recommendations = new ArrayList<>();
        
        // 命中率建议
        if (stats.hitRate() < 0.7) {
            recommendations.add("命中率较低(" + String.format("%.2f", stats.hitRate() * 100) + "%)，考虑增加缓存大小");
        }
        
        // 缓存大小效率
        if (stats.requestCount() > 100 && stats.hitRate() < 0.1) {
            recommendations.add("缓存效率较低，考虑减小缓存大小以节省内存");
        }
        
        // 高频访问建议
        if (stats.hitRate() > 0.95 && stats.requestCount() > 10000) {
            recommendations.add("高频热点数据，可考虑延长过期时间");
        }
        
        // 缓存满载警告
        // 注意：Caffeine的estimatedSize可能不会精确等于maximumSize
        if (estimatedSize > 800) { // 假设maximumSize是1000
            recommendations.add("缓存接近满载，考虑增加缓存大小");
        }
        
        return recommendations;
    }
    
    private int calculateHealthScore(CacheStats stats, long estimatedSize) {
        // 简单的健康度计算公式
        // 基于命中率(权重60%)、缓存使用率(权重40%)
        double hitRateScore = stats.hitRate() * 60;
        
        // 假设缓存最大大小为1000
        double sizeUtilization = Math.min((double) estimatedSize / 1000.0, 1.0);
        double sizeScore = (1.0 - Math.abs(sizeUtilization - 0.7)) * 40; // 70%使用率最佳
        
        return (int) Math.min(100, Math.max(0, hitRateScore + sizeScore));
    }
    
    /**
     * 缓存健康度检查
     * @return true表示健康，false表示需要关注
     */
    public boolean isCacheHealthy() {
        var analysis = getDetailedCacheAnalysis();
        for (Map<String, Object> cacheAnalysis : analysis.values()) {
            Integer healthScore = (Integer) cacheAnalysis.get("healthScore");
            if (healthScore != null && healthScore < 60) {
                return false; // 有任何一个缓存健康度低于60分就不健康
            }
        }
        return true;
    }
}
