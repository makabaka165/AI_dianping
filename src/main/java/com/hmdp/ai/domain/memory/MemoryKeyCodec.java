package com.hmdp.ai.domain.memory;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

@Component
public class MemoryKeyCodec {
    private static final String PREFIX = "hmdp:memory:v2:";

    public String encode(MemoryScope scope) {
        return PREFIX + scope.getMemoryType().name() + ':' + encodePart(scope.getTenantId()) + ':'
                + encodePart(scope.getWorkspaceId()) + ':' + encodePart(scope.getAgentId()) + ':'
                + encodePart(scope.getUserId()) + ':' + encodePart(scope.getSessionId()) + ':'
                + encodePart(scope.getResourceId());
    }

    public Optional<MemoryScope> decode(String key) {
        if (key == null || key.trim().isEmpty()) return Optional.empty();
        if (key.startsWith(PREFIX)) return decodeV2(key);
        return decodeLegacy(key);
    }

    private Optional<MemoryScope> decodeV2(String key) {
        String[] parts = key.split(":", -1);
        if (parts.length != 10) return Optional.empty();
        try {
            return Optional.of(new MemoryScope(decodePart(parts[4]), decodePart(parts[5]),
                    decodePart(parts[6]), decodePart(parts[7]), decodePart(parts[8]),
                    MemoryType.valueOf(parts[3]), decodePart(parts[9])));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public Optional<MemoryScope> decodeLegacy(String key) {
        String[] p = key.split(":", -1);
        if (p.length < 4 || !"memory".equals(p[1])) return Optional.empty();
        try {
            if ("shop".equals(p[2]) && "summary".equals(p[3]) && p.length >= 6)
                return legacy(MemoryType.SHOP_SUMMARY, p[5], p[4], "legacy");
            if ("shop".equals(p[2]) && "qa".equals(p[3]) && p.length >= 6)
                return legacy(MemoryType.SHOP_QA, p[5], p[4], "legacy");
            if ("shop".equals(p[2]) && "compare".equals(p[3]) && p.length >= 6)
                return legacy(MemoryType.SHOP_COMPARE, p[4], "", p[5]);
            if ("shop".equals(p[2]) && "recommend".equals(p[3]) && p.length >= 5)
                return legacy(MemoryType.SHOP_RECOMMEND, p[4], "", "legacy");
            if ("ai".equals(p[2]) && "chat".equals(p[3]) && p.length >= 6)
                return legacy(MemoryType.CONVERSATION, p[4], "", p[5]);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<MemoryScope> legacy(MemoryType type, String userId, String resourceId, String sessionId) {
        return Optional.of(new MemoryScope("legacy", "legacy", "legacy-shop-agent",
                blankTo(userId, "anonymous"), blankTo(sessionId, "legacy"), type, resourceId));
    }

    private String encodePart(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodePart(String value) {
        if (value.isEmpty()) return "";
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
