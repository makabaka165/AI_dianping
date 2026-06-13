package com.hmdp.ai.fallback;

public enum FallbackReason {
    MODEL_UNAVAILABLE,
    RATE_LIMITED,
    QUALITY_REJECTED,
    INSUFFICIENT_EVIDENCE,
    UNKNOWN_ERROR
}
