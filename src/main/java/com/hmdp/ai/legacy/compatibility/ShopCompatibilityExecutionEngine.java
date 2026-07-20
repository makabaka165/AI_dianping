package com.hmdp.ai.legacy.compatibility;

import com.hmdp.ai.application.ShopAIApplicationService;
import com.hmdp.dto.ai.ShopAIResponse;
import org.springframework.stereotype.Component;

/** Legacy adapter for the pre-platform shop-summary endpoints only. */
@Component
public class ShopCompatibilityExecutionEngine {
    private final ShopAIApplicationService shopAi;

    public ShopCompatibilityExecutionEngine(ShopAIApplicationService shopAi) {
        this.shopAi = shopAi;
    }

    public ShopAIResponse execute(String userId, String sessionId, String text, Long shopId, String endpoint) {
        return shopAi.chat(userId, sessionId, text, shopId, endpoint);
    }
}
