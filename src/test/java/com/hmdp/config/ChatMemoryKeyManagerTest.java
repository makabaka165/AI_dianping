package com.hmdp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryKeyManagerTest {

    private final ChatMemoryKeyManager keyManager = new ChatMemoryKeyManager();

    @Test
    void getFunctionTypeShouldKeepTwoSegmentShopType() {
        assertThat(keyManager.getFunctionType("hmdp:memory:shop:summary:1:10001"))
                .isEqualTo(ChatMemoryKeyManager.SHOP_SUMMARY_PREFIX);
        assertThat(keyManager.getFunctionType("hmdp:memory:shop:qa:1:10001"))
                .isEqualTo(ChatMemoryKeyManager.SHOP_QA_PREFIX);
    }

    @Test
    void getFunctionTypeShouldKeepTwoSegmentAiType() {
        assertThat(keyManager.getFunctionType("hmdp:memory:ai:chat:10001:default"))
                .isEqualTo(ChatMemoryKeyManager.AI_CHAT_PREFIX);
    }
}
