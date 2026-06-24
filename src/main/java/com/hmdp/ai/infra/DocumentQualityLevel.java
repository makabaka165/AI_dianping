package com.hmdp.ai.infra;

public enum DocumentQualityLevel {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR;

    public static DocumentQualityLevel fromScore(double score) {
        if (score >= 0.85) {
            return EXCELLENT;
        }
        if (score >= 0.70) {
            return GOOD;
        }
        if (score >= 0.45) {
            return FAIR;
        }
        return POOR;
    }
}
