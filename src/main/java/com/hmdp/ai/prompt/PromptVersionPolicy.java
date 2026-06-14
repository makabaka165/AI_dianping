package com.hmdp.ai.prompt;

import com.hmdp.dto.ai.ShopAIIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

@Component
public class PromptVersionPolicy {

    @Value("${hmdp.ai.prompt.canary.enabled:false}")
    private boolean canaryEnabled;

    @Value("${hmdp.ai.prompt.canary.ratio:0}")
    private double canaryRatio;

    public PromptTemplateRender render(ShopAIIntent intent,
                                       String stableVersion,
                                       String canaryVersion,
                                       String userId,
                                       String routeKey,
                                       String content) {
        boolean canary = shouldUseCanary(intent, userId, routeKey);
        return PromptTemplateRender.builder()
                .content(content)
                .version(canary ? nonBlank(canaryVersion, stableVersion) : stableVersion)
                .variant(canary ? "canary" : "stable")
                .build();
    }

    public boolean shouldUseCanary(ShopAIIntent intent, String userId, String routeKey) {
        double ratio = normalizedRatio(canaryRatio);
        if (!canaryEnabled || ratio <= 0) {
            return false;
        }
        String seed = safe(intent == null ? null : intent.name()) + ":"
                + safe(userId) + ":" + safe(routeKey);
        CRC32 crc32 = new CRC32();
        crc32.update(seed.getBytes(StandardCharsets.UTF_8));
        long bucket = crc32.getValue() % 10000;
        return bucket < Math.round(ratio * 10000);
    }

    private double normalizedRatio(double ratio) {
        if (ratio <= 0) {
            return 0;
        }
        if (ratio > 1) {
            return Math.min(1, ratio / 100.0);
        }
        return ratio;
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String safe(String value) {
        return value == null ? "none" : value.trim();
    }
}
