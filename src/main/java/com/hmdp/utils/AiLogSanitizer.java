package com.hmdp.utils;

public final class AiLogSanitizer {

    private static final int DEFAULT_LIMIT = 120;

    private AiLogSanitizer() {
    }

    public static String safe(String value) {
        return safe(value, DEFAULT_LIMIT);
    }

    public static String safe(String value, int limit) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, limit)) + "...";
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
}
