package com.hmdp.ai.memory;

import com.hmdp.ai.memory.ChatMemoryKeyManager;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.dto.ai.ShopSummaryResult;
import com.hmdp.ai.memory.RedissonChatMemoryStore;
import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.utils.LocalCacheManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MemoryService {

    private static final int SCAN_BATCH_SIZE = 100;
    private static final int EVIDENCE_SNIPPET_LIMIT = 300;

    @Resource
    private ChatMemoryKeyManager keyManager;

    @Resource
    private RedissonChatMemoryStore chatMemoryStore;

    @Resource(name = "memoryRedissonClient")
    private RedissonClient redissonClient;

    @Resource
    private LocalCacheManager localCacheManager;

    @Value("${hmdp.ai.memory.admin-scan-limit:10000}")
    private int adminScanLimit;

    public String aiChatKey(String userId, String sessionId) {
        return keyManager.buildAIChatKey(userId, sessionId);
    }

    public String shopSummaryKey(Long shopId, String userId) {
        return keyManager.buildShopSummaryKey(shopId, userId);
    }

    public String shopQAKey(Long shopId, String userId) {
        return keyManager.buildShopQAKey(shopId, userId);
    }

    public String shopCompareKey(String userId, String sessionId) {
        return keyManager.buildShopCompareKey(userId, sessionId);
    }

    public String shopRecommendKey(String userId) {
        return keyManager.buildShopRecommendKey(userId);
    }

    public void writeSummaryMemory(String memoryKey, ShopSummaryResult result, ShopAnalysisContext context) {
        try {
            List<ChatMessage> updated = new ArrayList<>();
            updated.add(UserMessage.from("生成店铺总结，店铺ID=" + result.getShopId()));
            updated.add(AiMessage.from("店铺总结：" + result.getCoreSummary()
                    + "\n关键点：" + String.join("、", result.getKeyPoints() == null ? Collections.emptyList() : result.getKeyPoints())
                    + "\n证据摘要：" + summarizeEvidence(context.safeEvidence())));
            chatMemoryStore.updateMessages(memoryKey, updated);
        } catch (Exception e) {
            log.warn("写入店铺总结记忆失败, memoryKey={}", AiLogSanitizer.safeKey(memoryKey), e);
        }
    }

    public String readSummaryMemory(String memoryKey) {
        try {
            List<ChatMessage> messages = chatMemoryStore.getMessages(memoryKey);
            if (messages == null || messages.isEmpty()) {
                return "暂无";
            }
            return messages.stream()
                    .map(ChatMessage::text)
                    .filter(text -> text != null && !text.trim().isEmpty())
                    .reduce((first, second) -> second)
                    .map(text -> AiLogSanitizer.safe(text, 800))
                    .orElse("暂无");
        } catch (Exception e) {
            log.warn("读取店铺总结记忆失败, memoryKey={}", AiLogSanitizer.safeKey(memoryKey), e);
            return "暂无";
        }
    }

    public void clearShopQAMemory(String userId, Long shopId) {
        delete(shopQAKey(shopId, userId));
    }

    public void clearShopSummaryMemory(String userId, Long shopId) {
        delete(shopSummaryKey(shopId, userId));
    }

    public int clearAllShopSummaryMemory(Long shopId) {
        if (shopId == null || shopId <= 0) {
            return 0;
        }
        String pattern = "hmdp:memory:" + ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX + ":" + shopId + ":*";
        int deleted = 0;
        try {
            List<String> indexedKeys = chatMemoryStore.getIndexedShopSummaryMemoryIds(shopId);
            if (!indexedKeys.isEmpty()) {
                return chatMemoryStore.deleteMemoryKeys(indexedKeys);
            }
            log.debug("店铺总结记忆索引为空，回退 bounded scan, shopId={}", shopId);
            List<String> keys = redissonClient.getKeys()
                    .getKeysStreamByPattern(pattern, SCAN_BATCH_SIZE)
                    .limit(scanLimit())
                    .collect(Collectors.toList());
            deleted += chatMemoryStore.deleteMemoryKeys(keys);
            return deleted;
        } catch (Exception e) {
            log.warn("清理店铺所有总结记忆失败, shopId={}", shopId, e);
            return deleted;
        }
    }

    public void clearRecommendMemory(String userId) {
        delete(shopRecommendKey(userId));
    }

    public Map<String, Integer> clearAllUserMemory(String userId) {
        Map<String, Integer> result = new HashMap<>();
        List<String> indexedKeys = chatMemoryStore.getIndexedMemoryIdsByUser(userId);
        if (!indexedKeys.isEmpty()) {
            int deleted = chatMemoryStore.deleteMemoryKeys(indexedKeys);
            result.put("index:user:" + userId, deleted);
            result.put("total", deleted);
            return result;
        }
        log.debug("用户记忆索引为空，回退 bounded scan, userId={}", AiLogSanitizer.safe(userId, 64));
        String appName = "hmdp";
        String[] patterns = {
                appName + ":memory:" + ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX + ":*:" + userId,
                appName + ":memory:" + ChatMemoryKeyManager.SHOP_QA_PREFIX + ":*:" + userId,
                appName + ":memory:" + ChatMemoryKeyManager.SHOP_COMPARE_PREFIX + ":" + userId + ":*",
                appName + ":memory:" + ChatMemoryKeyManager.SHOP_RECOMMEND_PREFIX + ":" + userId,
                appName + ":memory:" + ChatMemoryKeyManager.AI_CHAT_PREFIX + ":" + userId + ":*"
        };
        int totalDeleted = 0;
        for (String pattern : patterns) {
            int count = 0;
            List<String> keys = redissonClient.getKeys()
                    .getKeysStreamByPattern(pattern, SCAN_BATCH_SIZE)
                    .limit(scanLimit())
                    .collect(Collectors.toList());
            count += chatMemoryStore.deleteMemoryKeys(keys);
            result.put(pattern, count);
            totalDeleted += count;
        }
        result.put("total", totalDeleted);
        return result;
    }

    public int cleanupMemoryByFunction(String functionType) {
        return chatMemoryStore.deleteMessagesByFunction(functionType);
    }

    public boolean hasMemory(String memoryKey) {
        try {
            return chatMemoryStore.exists(memoryKey);
        } catch (Exception e) {
            log.warn("检查记忆是否存在失败, memoryKey={}", AiLogSanitizer.safeKey(memoryKey), e);
            return false;
        }
    }

    public int getMemoryMessageCount(String memoryKey) {
        try {
            List<ChatMessage> messages = chatMemoryStore.getMessages(memoryKey);
            return messages == null ? 0 : messages.size();
        } catch (Exception e) {
            log.warn("查询记忆消息数量失败, memoryKey={}", AiLogSanitizer.safeKey(memoryKey), e);
            return 0;
        }
    }

    public long getMemoryTtl(String memoryKey) {
        try {
            return chatMemoryStore.getTimeToLive(memoryKey);
        } catch (Exception e) {
            log.warn("获取记忆 TTL 失败, memoryKey={}", AiLogSanitizer.safeKey(memoryKey), e);
            return -1;
        }
    }

    public void refreshMemoryTtl(String memoryKey) {
        chatMemoryStore.refreshTtl(memoryKey);
    }

    public Map<String, Map<String, Integer>> getMemoryStats() {
        String cacheKey = LocalCacheManager.CacheKeys.MEMORY_STATS;
        Map<String, Map<String, Integer>> cachedStats = localCacheManager.get(
                cacheKey, Map.class, LocalCacheManager.CacheType.MEMORY_STATS);
        if (cachedStats != null) {
            return cachedStats;
        }
        Map<String, Map<String, Integer>> result = new HashMap<>();
        Map<String, RedissonChatMemoryStore.MemoryStats> allStats = chatMemoryStore.getAllMemoryStatistics();
        Map<String, Integer> statsSummary = new HashMap<>();
        int totalMessages = 0;
        for (Map.Entry<String, RedissonChatMemoryStore.MemoryStats> entry : allStats.entrySet()) {
            totalMessages += entry.getValue().getTotalMessages();
        }
        statsSummary.put("totalMemories", allStats.size());
        statsSummary.put("totalMessages", totalMessages);
        result.put("overview", statsSummary);
        if (scanLimit() > 0) {
            Map<String, Integer> scanGuard = new HashMap<>();
            scanGuard.put("adminScanLimit", scanLimit());
            result.put("scanGuard", scanGuard);
        }
        localCacheManager.put(cacheKey, result, LocalCacheManager.CacheType.MEMORY_STATS);
        return result;
    }

    public void refreshMemoryStatsCache() {
        String cacheKey = LocalCacheManager.CacheKeys.MEMORY_STATS;
        localCacheManager.remove(cacheKey, LocalCacheManager.CacheType.MEMORY_STATS);
        getMemoryStats();
    }

    private void delete(String memoryKey) {
        chatMemoryStore.deleteMessages(memoryKey);
    }

    private int scanLimit() {
        return adminScanLimit <= 0 ? 10000 : adminScanLimit;
    }

    private String summarizeEvidence(List<EvidenceItem> evidence) {
        return evidence.stream()
                .limit(3)
                .map(item -> "#" + item.getId() + ":" + truncate(item.getSnippet(), EVIDENCE_SNIPPET_LIMIT))
                .collect(Collectors.joining(" | "));
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
