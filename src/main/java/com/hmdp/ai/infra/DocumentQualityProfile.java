package com.hmdp.ai.infra;

import java.util.Locale;

public enum DocumentQualityProfile {
    GENERAL,
    PLATFORM_POLICY,
    SHOP_REVIEW;

    public static DocumentQualityProfile from(String value, DocumentQualityProfile fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return DocumentQualityProfile.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
