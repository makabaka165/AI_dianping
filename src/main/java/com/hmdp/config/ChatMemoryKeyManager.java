package com.hmdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ChatMemoryKeyManager {

    @Value("${app.name:hmdp}")
    private String appName;

    // 不同功能的前缀常量
    public static final String SHOP_SUMMARY_PREFIX = "shop:summary";
    public static final String SHOP_QA_PREFIX = "shop:qa";
    public static final String SHOP_COMPARE_PREFIX = "shop:compare";
    public static final String SHOP_RECOMMEND_PREFIX = "shop:recommend";
    public static final String AI_CHAT_PREFIX = "ai:chat";

    /**
     * 构建店铺总结记忆Key
     */
    public String buildShopSummaryKey(Long shopId, String userId) {
        return String.format("%s:memory:%s:%d:%s", appName, SHOP_SUMMARY_PREFIX, shopId, userId);
    }

    /**
     * 构建店铺问答记忆Key
     */
    public String buildShopQAKey(Long shopId, String userId) {
        return String.format("%s:memory:%s:%d:%s", appName, SHOP_QA_PREFIX, shopId, userId);
    }

    /**
     * 构建店铺对比记忆Key
     */
    public String buildShopCompareKey(String userId, String sessionId) {
        return String.format("%s:memory:%s:%s:%s", appName, SHOP_COMPARE_PREFIX, userId, sessionId);
    }

    /**
     * 构建店铺推荐记忆Key
     */
    public String buildShopRecommendKey(String userId) {
        return String.format("%s:memory:%s:%s", appName, SHOP_RECOMMEND_PREFIX, userId);
    }

    /**
     * 构建AI聊天记忆Key
     */
    public String buildAIChatKey(String userId, String sessionId) {
        return String.format("%s:memory:%s:%s:%s", appName, AI_CHAT_PREFIX, userId, sessionId);
    }

    /**
     * 通用构建方法
     */
    public String buildKey(String functionType, String... params) {
        StringBuilder key = new StringBuilder();
        key.append(appName).append(":memory:").append(functionType);
        for (String param : params) {
            key.append(":").append(param);
        }
        return key.toString();
    }

    /**
     * 解析Key获取功能类型
     */
    public String getFunctionType(String key) {
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
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9_.:-]", "_");
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
