package com.hmdp.ai.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ChatMemoryKeyManager {

    @Value("${app.name:hmdp}")
    private String appName = "hmdp";

    // 不同功能的前缀常量
    public static final String SHOP_SUMMARY_PREFIX = "shop:summary";
    public static final String SHOP_QA_PREFIX = "shop:qa";
    public static final String SHOP_COMPARE_PREFIX = "shop:compare";
    public static final String SHOP_RECOMMEND_PREFIX = "shop:recommend";
    public static final String AI_CHAT_PREFIX = "ai:chat";
    public static final int SESSION_ID_MAX_LENGTH = 64;
    public static final int KEY_SEGMENT_MAX_LENGTH = 128;

    /**
     * 构建店铺总结记忆Key
     */
    public String buildShopSummaryKey(Long shopId, String userId) {
        return String.format("%s:memory:%s:%d:%s", appName, SHOP_SUMMARY_PREFIX, shopId,
                normalizeKeySegment(userId, "anonymous", KEY_SEGMENT_MAX_LENGTH));
    }

    /**
     * 构建店铺问答记忆Key
     */
    public String buildShopQAKey(Long shopId, String userId) {
        return String.format("%s:memory:%s:%d:%s", appName, SHOP_QA_PREFIX, shopId,
                normalizeKeySegment(userId, "anonymous", KEY_SEGMENT_MAX_LENGTH));
    }

    /**
     * 构建店铺对比记忆Key
     */
    public String buildShopCompareKey(String userId, String sessionId) {
        return String.format("%s:memory:%s:%s:%s", appName, SHOP_COMPARE_PREFIX,
                normalizeKeySegment(userId, "anonymous", KEY_SEGMENT_MAX_LENGTH),
                normalizeSessionId(sessionId));
    }

    /**
     * 构建店铺推荐记忆Key
     */
    public String buildShopRecommendKey(String userId) {
        return String.format("%s:memory:%s:%s", appName, SHOP_RECOMMEND_PREFIX,
                normalizeKeySegment(userId, "anonymous", KEY_SEGMENT_MAX_LENGTH));
    }

    /**
     * 构建AI聊天记忆Key
     */
    public String buildAIChatKey(String userId, String sessionId) {
        return String.format("%s:memory:%s:%s:%s", appName, AI_CHAT_PREFIX,
                normalizeKeySegment(userId, "anonymous", KEY_SEGMENT_MAX_LENGTH),
                normalizeSessionId(sessionId));
    }

    /**
     * 通用构建方法
     */
    public String buildKey(String functionType, String... params) {
        StringBuilder key = new StringBuilder();
        key.append(appName).append(":memory:").append(functionType);
        for (String param : params) {
            key.append(":").append(normalizeKeySegment(param, "default", KEY_SEGMENT_MAX_LENGTH));
        }
        return key.toString();
    }

    public String normalizeSessionId(String sessionId) {
        return normalizeKeySegment(sessionId, "default", SESSION_ID_MAX_LENGTH);
    }

    public String normalizeKeySegment(String value, String defaultValue, int maxLength) {
        String fallback = defaultValue == null || defaultValue.trim().isEmpty() ? "default" : defaultValue.trim();
        int safeMax = maxLength <= 0 ? KEY_SEGMENT_MAX_LENGTH : maxLength;
        String text = value == null ? fallback : value.trim();
        if (text.isEmpty()) {
            text = fallback;
        }
        text = text.replaceAll("[^a-zA-Z0-9_.-]", "_");
        if (text.length() > safeMax) {
            text = text.substring(0, safeMax);
        }
        if (text.isEmpty()) {
            return fallback.replaceAll("[^a-zA-Z0-9_.-]", "_");
        }
        return text;
    }

    /**
     * 解析Key获取功能类型
     */
    public String getFunctionType(String key) {
        if (key == null || key.trim().isEmpty()) {
            return "unknown";
        }
        String[] parts = key.split(":");
        if (parts.length < 4 || !"memory".equals(parts[1])) {
            return "unknown";
        }
        if ("shop".equals(parts[2]) && parts.length >= 4) {
            return parts[2] + ":" + parts[3];
        }
        if ("ai".equals(parts[2]) && parts.length >= 4) {
            return parts[2] + ":" + parts[3];
        }
        return parts[2];
    }

    /**
     * 构建模式匹配的Key（用于批量操作）
     */
    public String buildPatternKey(String functionType) {
        return String.format("%s:memory:%s:*", appName, functionType);
    }

    public String buildUserIndexKey(String userId) {
        return String.format("%s:memory:index:user:%s", appName, safeIndexSegment(userId));
    }

    public String buildFunctionIndexKey(String functionType) {
        return String.format("%s:memory:index:function:%s", appName, safeIndexSegment(functionType));
    }

    public String buildShopSummaryIndexKey(Long shopId) {
        return String.format("%s:memory:index:shop-summary:%d", appName, shopId);
    }

    public String getUserId(String key) {
        String[] parts = key == null ? new String[0] : key.split(":");
        String functionType = getFunctionType(key == null ? "" : key);
        if (SHOP_SUMMARY_PREFIX.equals(functionType) || SHOP_QA_PREFIX.equals(functionType)) {
            return parts.length > 5 ? parts[5] : null;
        }
        if (SHOP_COMPARE_PREFIX.equals(functionType) || AI_CHAT_PREFIX.equals(functionType)) {
            return parts.length > 4 ? parts[4] : null;
        }
        if (SHOP_RECOMMEND_PREFIX.equals(functionType)) {
            return parts.length > 4 ? parts[4] : null;
        }
        return null;
    }

    public Long getShopSummaryShopId(String key) {
        String[] parts = key == null ? new String[0] : key.split(":");
        if (!SHOP_SUMMARY_PREFIX.equals(getFunctionType(key == null ? "" : key)) || parts.length <= 4) {
            return null;
        }
        try {
            return Long.parseLong(parts[4]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String safeIndexSegment(String value) {
        return normalizeKeySegment(value, "unknown", KEY_SEGMENT_MAX_LENGTH);
    }

    /**
     * 获取所有功能类型数组
     */
    public static String[] getAllFunctionTypes() {
        return new String[] {
                SHOP_SUMMARY_PREFIX,
                SHOP_QA_PREFIX,
                SHOP_COMPARE_PREFIX,
                SHOP_RECOMMEND_PREFIX,
                AI_CHAT_PREFIX
        };
    }
}
