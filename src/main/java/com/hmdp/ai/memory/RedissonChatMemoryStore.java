package com.hmdp.ai.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.memory.MemoryPolicyService;
import com.hmdp.ai.domain.memory.MemoryKeyCodec;
import com.hmdp.utils.LocalCacheManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Slf4j
public class RedissonChatMemoryStore implements ChatMemoryStore {

    private static final int SCAN_BATCH_SIZE = 100;
    private static final long INDEX_TTL_PADDING_SECONDS = 86400L;

    private final RedissonClient redissonClient;
    private final ChatMemoryKeyManager keyManager;
    private final ObjectMapper objectMapper;
    private final MemoryKeyCodec memoryKeyCodec;
    private final MemoryPolicyService memoryPolicyService;

    @Value("${chat.memory.redis.ttl:7200}")
    private long defaultTtlSeconds;

    @Value("${chat.memory.ttl.shop-summary:3600}")
    private long shopSummaryTtlSeconds;

    @Value("${chat.memory.ttl.shop-qa:7200}")
    private long shopQaTtlSeconds;

    @Value("${chat.memory.ttl.shop-compare:1800}")
    private long shopCompareTtlSeconds;

    @Value("${chat.memory.ttl.shop-recommend:86400}")
    private long shopRecommendTtlSeconds;

    @Value("${chat.memory.ttl.ai-chat:3600}")
    private long aiChatTtlSeconds;

    // 是否开启详细日志（可以通过配置控制）
    @Value("${chat.memory.debug.enabled:false}")
    private boolean debugEnabled;

    @Value("${hmdp.ai.memory.admin-scan-limit:10000}")
    private int adminScanLimit;

    // 注入本地缓存管理器
    @Autowired
    private LocalCacheManager localCacheManager;

    // 缓存TTL配置
    private Map<String, Long> ttlConfigCache;

    // ========== 构造函数注入（推荐方式） ==========
    @Autowired
    public RedissonChatMemoryStore(@Qualifier("memoryRedissonClient") RedissonClient redissonClient,
                                   ChatMemoryKeyManager keyManager,
                                   MemoryKeyCodec memoryKeyCodec,
                                   MemoryPolicyService memoryPolicyService) {
        this.redissonClient = redissonClient;
        this.keyManager = keyManager;
        this.objectMapper = new ObjectMapper();
        this.memoryKeyCodec = memoryKeyCodec;
        this.memoryPolicyService = memoryPolicyService;
        log.info("RedissonChatMemoryStore 初始化完成");
    }

    public RedissonChatMemoryStore(RedissonClient redissonClient, ChatMemoryKeyManager keyManager) {
        this.redissonClient = redissonClient;
        this.keyManager = keyManager;
        this.objectMapper = new ObjectMapper();
        this.memoryKeyCodec = new MemoryKeyCodec();
        this.memoryPolicyService = new MemoryPolicyService(defaultPolicyTtls());
    }

    @PostConstruct
    public void init() {
        // 初始化TTL配置缓存
        ttlConfigCache = new ConcurrentHashMap<>();
        ttlConfigCache.put(ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX, positiveOrDefault(shopSummaryTtlSeconds, 3600L));
        ttlConfigCache.put(ChatMemoryKeyManager.SHOP_QA_PREFIX, positiveOrDefault(shopQaTtlSeconds, 7200L));
        ttlConfigCache.put(ChatMemoryKeyManager.SHOP_COMPARE_PREFIX, positiveOrDefault(shopCompareTtlSeconds, 1800L));
        ttlConfigCache.put(ChatMemoryKeyManager.SHOP_RECOMMEND_PREFIX, positiveOrDefault(shopRecommendTtlSeconds, 86400L));
        ttlConfigCache.put(ChatMemoryKeyManager.AI_CHAT_PREFIX, positiveOrDefault(aiChatTtlSeconds, 3600L));
    }

    @Autowired(required = false)
    public void setKeyManager(ChatMemoryKeyManager keyManager) {
        // 只在构造函数注入失败时使用
    }

    @Autowired(required = false)
    public void setLocalCacheManager(LocalCacheManager localCacheManager) {
        this.localCacheManager = localCacheManager;
    }

    /**
     * 从 Redis 获取 JSON 字符串 → 反序列化为 SimpleChatMessage 列表。
     * 通过 convertToLangChainMessage 转换为 ChatMessage 接口的实现类，供 AI 模型使用
     * @param memoryId
     * @return
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = memoryId.toString();
        String json;

        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            json = bucket.get();
        } catch (RuntimeException e) {
            log.error("读取记忆失败: {}", getShortKey(key), e);
            return new ArrayList<>();
        }

        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            //将从Redis获取的JSON字符串反序列化为SimpleChatMessage对象列表
            //通过 getTypeFactory().constructCollectionType 指定目标类型为包含SimpleChatMessage元素的List集合
            //将json转换为java对象
            List<SimpleChatMessage> simpleMessages = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, SimpleChatMessage.class));

            List<ChatMessage> messages = simpleMessages.stream()
                    .map(this::convertToLangChainMessage)
                    .filter(message -> message != null) // 过滤掉null消息
                    .collect(Collectors.toList());

            // 只在调试模式或首次获取时输出日志
            if (debugEnabled || messages.size() == 1) {
                log.debug("获取记忆: {} ({}条消息)", getShortKey(key), messages.size());
            }

            return messages;
        } catch (JsonProcessingException e) {
            log.error("记忆数据格式损坏: {}", getShortKey(key), e);
            try {
                deleteMessages(memoryId);
                log.info("已清理损坏的记忆数据: {}", getShortKey(key));
            } catch (Exception cleanupError) {
                log.warn("清理损坏数据失败: {}", getShortKey(key));
            }
            return new ArrayList<>();
        } catch (RuntimeException e) {
            log.error("转换记忆失败: {}", getShortKey(key), e);
            return new ArrayList<>();
        }
    }

    /**
     * 用户输入或 AI 回复的 ChatMessage 列表通过 convertToSimpleMessage 转换为 SimpleChatMessage。
     * 使用 ObjectMapper 序列化为 JSON 字符串存储到 Redis
     * @param memoryId
     * @param messages
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = memoryId.toString();

        try {
            // 过滤和验证消息
            List<SimpleChatMessage> simpleMessages = messages.stream()
                    .filter(this::isValidChatMessage) // 添加消息验证
                    .map(this::convertToSimpleMessage)
                    .filter(simpleMsg -> isValidSimpleMessage(simpleMsg)) // 验证转换后的消息
                    .collect(Collectors.toList());

            String json = objectMapper.writeValueAsString(simpleMessages);
            RBucket<String> bucket = redissonClient.getBucket(key);

            long ttl = getTtlByFunctionType(key);
            bucket.set(json, ttl, TimeUnit.SECONDS);
            indexMemory(key, ttl);

            // 简化日志：只在有意义的变化时输出
            int messageCount = simpleMessages.size();
            if (debugEnabled || messageCount == 1 || messageCount % 5 == 0) {
                log.debug("保存记忆: {} ({}条消息, TTL: {}分钟)",
                        getShortKey(key), messageCount, ttl / 60);
            }

        } catch (JsonProcessingException e) {
            log.error("序列化消息失败: {}", getShortKey(key), e);
            throw new RuntimeException("保存记忆失败", e);
        } catch (Exception e) {
            log.error("更新记忆失败: {}", getShortKey(key), e);
            throw new RuntimeException("保存记忆失败", e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = memoryId.toString();

        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            boolean deleted = bucket.delete();
            removeMemoryIndexes(key);

            if (deleted) {
                log.info("删除记忆: {}", getShortKey(key));
            } else if (debugEnabled) {
                log.debug("记忆不存在: {}", getShortKey(key));
            }

        } catch (Exception e) {
            log.error("删除记忆失败: {}", getShortKey(key), e);
            throw new RuntimeException("删除记忆失败", e);
        }
    }

    /**
     * 验证ChatMessage是否有效
     */
    private boolean isValidChatMessage(ChatMessage message) {
        if (message == null) {
            log.warn("ChatMessage为null，跳过");
            return false;
        }

        try {
            String text = message.text();
            if (text == null || text.trim().isEmpty()) {
                log.warn("ChatMessage文本为空，类型: {}，跳过", message.getClass().getSimpleName());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("验证ChatMessage失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 验证SimpleChatMessage是否有效
     */
    private boolean isValidSimpleMessage(SimpleChatMessage simpleMessage) {
        if (simpleMessage == null) {
            return false;
        }

        String text = simpleMessage.getText();
        String type = simpleMessage.getType();

        return text != null && !text.trim().isEmpty() && type != null;
    }

    /**
     * 将 LangChain ChatMessage 转换为简单消息
     */
    private SimpleChatMessage convertToSimpleMessage(ChatMessage message) {
        try {
            String type = getMessageType(message);
            String text = cleanMessageText(message.text());
            return new SimpleChatMessage(type, text);
        } catch (Exception e) {
            log.error("转换为SimpleChatMessage失败: {}", e.getMessage());
            // 返回一个安全的默认消息
            return new SimpleChatMessage("USER", "消息转换失败");
        }
    }

    /**
     * 清理和验证消息文本
     */
    private String cleanMessageText(String text) {
        if (text == null) {
            return "空消息";
        }

        text = text.trim();
        if (text.isEmpty()) {
            return "空消息";
        }

        // 限制消息长度，避免过长的内容
        if (text.length() > 10000) {
            text = text.substring(0, 10000) + "...";
            log.debug("消息内容过长，已截断");
        }

        return text;
    }

    /**
     * 根据消息实例类型获取消息类型字符串
     */
    private String getMessageType(ChatMessage message) {
        if (message instanceof SystemMessage) {
            return "SYSTEM";
        } else if (message instanceof UserMessage) {
            return "USER";
        } else if (message instanceof AiMessage) {
            return "AI";
        } else {
            String className = message.getClass().getSimpleName();

            if (className.toLowerCase().contains("system")) {
                return "SYSTEM";
            } else if (className.toLowerCase().contains("user")) {
                return "USER";
            } else if (className.toLowerCase().contains("ai") ||
                    className.toLowerCase().contains("assistant")) {
                return "AI";
            } else {
                if (debugEnabled) {
                    log.warn("无法识别消息类型: {}，默认使用USER", className);
                }
                return "USER";
            }
        }
    }

    /**
     * 将simpleMessage转换为 LangChain ChatMessage
     * 用户输入：用户发送的文本会被封装为 SimpleChatMessage（类型为 USER）。
     * AI 回复：AI 生成的文本会被封装为 SimpleChatMessage（类型为 AI）。
     * 系统消息：初始化对话或提示信息（类型为 SYSTEM）
     */
    private ChatMessage convertToLangChainMessage(SimpleChatMessage simpleMessage) {
        try {
            if (simpleMessage == null) {
                log.warn("SimpleChatMessage为null，跳过转换");
                return null;
            }

            String type = simpleMessage.getType();
            String text = simpleMessage.getText();

            // 验证和清理文本内容
            if (text == null || text.trim().isEmpty()) {
                log.warn("消息文本为空，类型: {}，使用默认文本", type);
                text = getDefaultTextForType(type);
            }

            // 最终验证
            if (text == null || text.trim().isEmpty()) {
                log.warn("无法为类型 {} 生成有效文本，跳过消息", type);
                return null;
            }

            text = text.trim();

            if (type == null) {
                return UserMessage.from(text);
            }

            switch (type.toUpperCase()) {
                case "SYSTEM":
                    return SystemMessage.from(text);
                case "USER":
                    return UserMessage.from(text);
                case "AI":
                    return AiMessage.from(text);
                default:
                    if (debugEnabled) {
                        log.warn("未知消息类型: {}，默认使用USER类型", type);
                    }
                    return UserMessage.from(text);
            }
        } catch (Exception e) {
            log.error("转换为LangChain消息失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 为不同类型获取默认文本
     */
    private String getDefaultTextForType(String type) {
        if (type == null) {
            return "默认消息";
        }

        switch (type.toUpperCase()) {
            case "SYSTEM":
                return "系统消息";
            case "USER":
                return "用户消息";
            case "AI":
                return "AI正在思考...";
            default:
                return "默认消息";
        }
    }

    /**
     * 获取简化的key用于日志显示
     */
    private String getShortKey(String key) {
        if (key == null) return "null";

        // 如果是默认key，直接返回
        if ("default".equals(key)) {
            return "default";
        }

        // 提取有意义的部分
        if (key.contains(":memory:")) {
            String[] parts = key.split(":");
            if (parts.length >= 4) {
                // 格式如: hmdp:memory:shop:summary:1:user123
                String type = parts[2] + ":" + parts[3]; // shop:summary
                String id = parts.length > 4 ? parts[4] : "";
                return type + (id.isEmpty() ? "" : ":" + id);
            }
        }

        // 如果key太长，截取后面部分
        if (key.length() > 30) {
            return "..." + key.substring(key.length() - 27);
        }

        return key;
    }

    /**
     * 检查记忆是否存在
     */
    public boolean exists(Object memoryId) {
        String key = memoryId.toString();
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            return bucket.isExists();
        } catch (Exception e) {
            log.error("检查记忆存在性失败: {}", getShortKey(key), e);
            return false;
        }
    }

    /**
     * 获取记忆的剩余生存时间
     */
    public long getTimeToLive(Object memoryId) {
        String key = memoryId.toString();
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            return bucket.remainTimeToLive();
        } catch (Exception e) {
            log.error("获取记忆TTL失败: {}", getShortKey(key), e);
            return -1;
        }
    }

    /**
     * 刷新记忆的过期时间
     */
    public void refreshTtl(Object memoryId) {
        String key = memoryId.toString();
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            if (bucket.isExists()) {
                long ttl = getTtlByFunctionType(key);
                bucket.expire(ttl, TimeUnit.SECONDS);
                if (debugEnabled) {
                    log.debug("刷新记忆TTL: {} ({}分钟)", getShortKey(key), ttl / 60);
                }
            }
        } catch (Exception e) {
            log.error("刷新记忆TTL失败: {}", getShortKey(key), e);
        }
    }

    /**
     * 按功能类型批量删除
     */
    public int deleteMessagesByFunction(String functionType) {
        String pattern = keyManager.buildPatternKey(functionType);

        try {
            List<String> keys = getIndexedMemoryIdsByFunction(functionType);
            if (keys.isEmpty()) {
                log.debug("Memory function index is empty, fallback to bounded scan, functionType={}", functionType);
                keys = scanKeys(pattern);
            }
            int count = 0;
            for (String key : keys) {
                count += deleteMemoryKey(key);
            }

            if (count > 0) {
                log.info("批量删除记忆: {} ({}条)", functionType, count);
            }
            return count;
        } catch (Exception e) {
            log.error("批量删除记忆失败: {}", functionType, e);
            throw new RuntimeException("批量删除失败", e);
        }
    }

    /**
     * 获取功能类型的记忆统计
     */
    public Map<String, Integer> getMemoryStatsByFunction(String functionType) {
        String pattern = keyManager.buildPatternKey(functionType);
        Map<String, Integer> stats = new HashMap<>();

        try {
            List<String> keys = getIndexedMemoryIdsByFunction(functionType);
            if (keys.isEmpty()) {
                log.debug("Memory function index is empty, fallback to bounded scan for stats, functionType={}", functionType);
                keys = scanKeys(pattern);
            }
            int totalKeys = 0;
            int totalMessages = 0;

            for (String key : keys) {
                totalKeys++;
                List<ChatMessage> messages = getMessages(key);
                totalMessages += messages.size();
            }

            stats.put("totalSessions", totalKeys);
            stats.put("totalMessages", totalMessages);

            if (debugEnabled && totalKeys > 0) {
                log.debug("功能统计 {}: {}个会话, {}条消息", functionType, totalKeys, totalMessages);
            }

            return stats;
        } catch (Exception e) {
            log.error("获取记忆统计失败: {}", functionType, e);
            return new HashMap<>();
        }
    }

    private List<String> scanKeys(String pattern) {
        return redissonClient.getKeys()
                .getKeysStreamByPattern(pattern, SCAN_BATCH_SIZE)
                .limit(scanLimit())
                .collect(Collectors.toList());
    }

    public List<String> getIndexedMemoryIdsByUser(String userId) {
        return validIndexedKeys(keyManager.buildUserIndexKey(userId));
    }

    public List<String> getIndexedMemoryIdsByFunction(String functionType) {
        return validIndexedKeys(keyManager.buildFunctionIndexKey(functionType));
    }

    public List<String> getIndexedShopSummaryMemoryIds(Long shopId) {
        if (shopId == null || shopId <= 0) {
            return new ArrayList<>();
        }
        return validIndexedKeys(keyManager.buildShopSummaryIndexKey(shopId));
    }

    public int deleteMemoryKeys(Collection<String> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (String memoryId : memoryIds) {
            deleted += deleteMemoryKey(memoryId);
        }
        return deleted;
    }

    private int deleteMemoryKey(String memoryId) {
        if (memoryId == null || memoryId.trim().isEmpty()) {
            return 0;
        }
        try {
            long deleted = redissonClient.getKeys().unlink(memoryId);
            removeMemoryIndexes(memoryId);
            return (int) deleted;
        } catch (Exception e) {
            log.warn("Delete indexed memory failed: {}", getShortKey(memoryId), e);
            return 0;
        }
    }

    private List<String> validIndexedKeys(String indexKey) {
        List<String> keys = new ArrayList<>();
        try {
            RSet<String> index = redissonClient.getSet(indexKey);
            if (index == null) {
                return keys;
            }
            if (!index.isExists()) {
                return keys;
            }
            for (String memoryId : index.readAll()) {
                if (keys.size() >= scanLimit()) {
                    break;
                }
                if (exists(memoryId)) {
                    keys.add(memoryId);
                } else {
                    index.remove(memoryId);
                }
            }
        } catch (Exception e) {
            log.warn("Read memory index failed: {}", getShortKey(indexKey), e);
        }
        return keys;
    }

    private void indexMemory(String memoryId, long ttlSeconds) {
        try {
            String functionType = keyManager.getFunctionType(memoryId);
            if ("unknown".equals(functionType)) {
                return;
            }
            long indexTtl = Math.max(defaultTtlSeconds, ttlSeconds) + INDEX_TTL_PADDING_SECONDS;
            addToIndex(keyManager.buildFunctionIndexKey(functionType), memoryId, indexTtl);

            String userId = keyManager.getUserId(memoryId);
            if (userId != null && !userId.trim().isEmpty()) {
                addToIndex(keyManager.buildUserIndexKey(userId), memoryId, indexTtl);
            }

            Long shopId = keyManager.getShopSummaryShopId(memoryId);
            if (shopId != null && shopId > 0) {
                addToIndex(keyManager.buildShopSummaryIndexKey(shopId), memoryId, indexTtl);
            }
        } catch (Exception e) {
            log.warn("Index memory failed: {}", getShortKey(memoryId), e);
        }
    }

    private void addToIndex(String indexKey, String memoryId, long ttlSeconds) {
        RSet<String> index = redissonClient.getSet(indexKey);
        index.add(memoryId);
        index.expire(ttlSeconds, TimeUnit.SECONDS);
    }

    private void removeMemoryIndexes(String memoryId) {
        try {
            String functionType = keyManager.getFunctionType(memoryId);
            if (!"unknown".equals(functionType)) {
                removeFromIndex(keyManager.buildFunctionIndexKey(functionType), memoryId);
            }
            String userId = keyManager.getUserId(memoryId);
            if (userId != null && !userId.trim().isEmpty()) {
                removeFromIndex(keyManager.buildUserIndexKey(userId), memoryId);
            }
            Long shopId = keyManager.getShopSummaryShopId(memoryId);
            if (shopId != null && shopId > 0) {
                removeFromIndex(keyManager.buildShopSummaryIndexKey(shopId), memoryId);
            }
        } catch (Exception e) {
            log.warn("Remove memory indexes failed: {}", getShortKey(memoryId), e);
        }
    }

    private void removeFromIndex(String indexKey, String memoryId) {
        if (indexKey == null || indexKey.trim().isEmpty()) {
            return;
        }
        try {
            redissonClient.getSet(indexKey).remove(memoryId);
        } catch (Exception e) {
            log.debug("Remove memory index member failed: index={}, memory={}", getShortKey(indexKey), getShortKey(memoryId), e);
        }
    }

    private int scanLimit() {
        return adminScanLimit <= 0 ? 10000 : adminScanLimit;
    }

    /**
     * 根据功能类型获取TTL（使用本地缓存优化）
     */
    private long getTtlByFunctionType(String key) {
        java.util.Optional<com.hmdp.ai.domain.memory.MemoryScope> scope = memoryKeyCodec.decode(key);
        if (scope.isPresent()) {
            return memoryPolicyService.ttlSeconds(scope.get());
        }
        String functionType = keyManager.getFunctionType(key);
        
        // 从本地缓存获取TTL配置
        Long cachedTtl = ttlConfigCache.get(functionType);
        if (cachedTtl != null) {
            return cachedTtl;
        }

        // 如果缓存中没有，则使用默认值
        return defaultTtlSeconds;
    }

    private static Map<com.hmdp.ai.domain.memory.MemoryType, Long> defaultPolicyTtls() {
        Map<com.hmdp.ai.domain.memory.MemoryType, Long> values = new java.util.EnumMap<>(com.hmdp.ai.domain.memory.MemoryType.class);
        values.put(com.hmdp.ai.domain.memory.MemoryType.SHOP_SUMMARY, 3600L);
        values.put(com.hmdp.ai.domain.memory.MemoryType.SHOP_QA, 7200L);
        values.put(com.hmdp.ai.domain.memory.MemoryType.SHOP_COMPARE, 1800L);
        values.put(com.hmdp.ai.domain.memory.MemoryType.SHOP_RECOMMEND, 86400L);
        values.put(com.hmdp.ai.domain.memory.MemoryType.CONVERSATION, 7200L);
        return values;
    }

    private long positiveOrDefault(long value, long fallback) {
        return value <= 0 ? fallback : value;
    }

    /**
     * 清理所有损坏的记忆数据
     */
    public int cleanupCorruptedMemories() {
        int cleanedCount = 0;
        try {
            // 获取所有记忆相关的key
            List<String> keys = scanKeys("*memory*");

            for (String key : keys) {
                try {
                    List<ChatMessage> messages = getMessages(key);
                    // 如果获取消息成功，说明数据正常
                } catch (Exception e) {
                    // 如果获取失败，删除这个key
                    try {
                        redissonClient.getBucket(key).delete();
                        cleanedCount++;
                        log.info("清理损坏的记忆: {}", getShortKey(key));
                    } catch (Exception deleteError) {
                        log.warn("删除损坏记忆失败: {}", getShortKey(key));
                    }
                }
            }

            if (cleanedCount > 0) {
                log.info("清理完成，共清理 {} 个损坏的记忆", cleanedCount);
            }

        } catch (Exception e) {
            log.error("清理损坏记忆失败", e);
        }

        return cleanedCount;
    }


    /**
     * 批量获取记忆统计
     */
    public Map<String, MemoryStats> getAllMemoryStatistics() {
        Map<String, MemoryStats> allStats = new HashMap<>();
        String[] functionTypes = ChatMemoryKeyManager.getAllFunctionTypes();

        for (String functionType : functionTypes) {
            Map<String, Integer> stats = getMemoryStatsByFunction(functionType);
            allStats.put(functionType, MemoryStats.builder()
                    .functionType(functionType)
                    .totalSessions(stats.getOrDefault("totalSessions", 0))
                    .totalMessages(stats.getOrDefault("totalMessages", 0))
                    .build());
        }

        return allStats;
    }

    @Data
    @Builder
    public static class MemoryStats {
        private String functionType;
        private int totalSessions;
        private int totalMessages;
        
        // 添加getter方法
        public String getFunctionType() {
            return functionType;
        }
        
        public int getTotalSessions() {
            return totalSessions;
        }
        
        public int getTotalMessages() {
            return totalMessages;
        }
    }

}
