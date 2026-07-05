package com.hmdp.ai.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryKeyManagerTest {

    private final ChatMemoryKeyManager keyManager = new ChatMemoryKeyManager();

    @Test
    void getFunctionTypeShouldKeepTwoSegmentShopType() {
        assertThat(keyManager.getFunctionType(keyManager.buildShopSummaryKey(1L, "10001")))
                .isEqualTo(ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX);
        assertThat(keyManager.getFunctionType(keyManager.buildShopQAKey(1L, "10001")))
                .isEqualTo(ChatMemoryKeyManager.SHOP_QA_PREFIX);
        assertThat(keyManager.getFunctionType(keyManager.buildShopCompareKey("10001", "s1")))
                .isEqualTo(ChatMemoryKeyManager.SHOP_COMPARE_PREFIX);
        assertThat(keyManager.getFunctionType(keyManager.buildShopRecommendKey("10001")))
                .isEqualTo(ChatMemoryKeyManager.SHOP_RECOMMEND_PREFIX);
    }

    @Test
    void getFunctionTypeShouldKeepTwoSegmentAiType() {
        assertThat(keyManager.getFunctionType(keyManager.buildAIChatKey("10001", "default")))
                .isEqualTo(ChatMemoryKeyManager.AI_CHAT_PREFIX);
    }

    @Test
    void getFunctionTypeShouldReturnUnknownForUnknownOrBlankKey() {
        assertThat(keyManager.getFunctionType("not-a-memory-key")).isEqualTo("unknown");
        assertThat(keyManager.getFunctionType(null)).isEqualTo("unknown");
        assertThat(keyManager.getFunctionType("")).isEqualTo("unknown");
    }

    @Test
    void normalizeSessionIdShouldReturnDefaultForBlank() {
        assertThat(keyManager.normalizeSessionId(null)).isEqualTo("default");
        assertThat(keyManager.normalizeSessionId("   ")).isEqualTo("default");
    }

    @Test
    void normalizeSessionIdShouldRemoveUnsafeCharactersAndLimitLength() {
        String normalized = keyManager.normalizeSessionId("abc:def/中文");

        assertThat(normalized).doesNotContain(":", "/", "中文");
        assertThat(normalized).isEqualTo("abc_def___");
        assertThat(keyManager.normalizeSessionId("a".repeat(80))).hasSize(64);
    }

    @Test
    void buildAIChatKeyShouldNormalizeUnsafeSessionSegment() {
        String key = keyManager.buildAIChatKey("10001", "a:b");

        assertThat(key).isEqualTo("hmdp:memory:ai:chat:10001:a_b");
        assertThat(keyManager.getFunctionType(key)).isEqualTo(ChatMemoryKeyManager.AI_CHAT_PREFIX);
        assertThat(keyManager.getUserId(key)).isEqualTo("10001");
    }
}
