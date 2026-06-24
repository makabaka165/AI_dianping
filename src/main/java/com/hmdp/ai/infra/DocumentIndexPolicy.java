package com.hmdp.ai.infra;

import java.util.Locale;

public enum DocumentIndexPolicy {
    OBSERVE_ONLY,
    SKIP_LOW_QUALITY,
    TAG_LOW_QUALITY,
    DEGRADE_LOW_QUALITY,
    SAMPLE_REVIEW;

    public static DocumentIndexPolicy from(String value, DocumentIndexPolicy fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return DocumentIndexPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
