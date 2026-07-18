package com.hmdp.ai.infra;

public final class AiLogSanitizer {

    private static final int DEFAULT_LIMIT = 120;
    private static final String REDACTED = "***";

    private AiLogSanitizer() {
    }

    public static String safe(String value) {
        return safe(value, DEFAULT_LIMIT);
    }

    public static String safe(String value, int limit) {
        if (value == null) {
            return null;
        }
        String normalized = redact(value).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, limit)) + "...";
    }

    public static boolean containsSensitive(String value) {
        if (value == null) {
            return false;
        }
        String text = value.toLowerCase();
        return text.contains("api_key")
                || text.contains("apikey")
                || text.contains("api-key")
                || text.contains("authorization")
                || text.contains("password")
                || text.contains("secret")
                || text.contains("token")
                || text.contains("sk-")
                || text.contains("sql")
                || text.contains("select ")
                || text.contains("insert ")
                || text.contains("update ")
                || text.contains("delete ")
                || text.contains(" at com.");
    }

    public static String safeKey(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 16) {
            return value;
        }
        return value.substring(0, 8) + "***" + value.substring(value.length() - 4);
    }

    private static String redact(String value) {
        return value
                .replaceAll("(?i)(authorization)[\"']?\\s*[:=]\\s*[\"']?(bearer\\s+)?[^\\s,;\"'}]+", "$1=" + REDACTED)
                .replaceAll("(?i)(api[_-]?key|apikey|password|secret|token)[\"']?\\s*[:=]\\s*[\"']?[^\\s,;\"'}]+", "$1=" + REDACTED)
                .replaceAll("sk-[A-Za-z0-9_-]{6,}", "sk-" + REDACTED);
    }
}
