package com.hmdp.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCacheManagerRateLimitTest {

    private LocalCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new LocalCacheManager();
        cacheManager.init();
    }

    @Test
    void shouldAllowCallsWithinSameWindowBeforeLimit() {
        assertThat(cacheManager.checkAndIncrementUserCallCount("u1", "tool", 2, 60_000, 1_000))
                .isTrue();
        assertThat(cacheManager.checkAndIncrementUserCallCount("u1", "tool", 2, 60_000, 2_000))
                .isTrue();
    }

    @Test
    void shouldRejectCallAfterLimitInSameWindow() {
        assertThat(cacheManager.checkAndIncrementUserCallCount("u1", "tool", 2, 60_000, 1_000))
                .isTrue();
        assertThat(cacheManager.checkAndIncrementUserCallCount("u1", "tool", 2, 60_000, 2_000))
                .isTrue();
        assertThat(cacheManager.checkAndIncrementUserCallCount("u1", "tool", 2, 60_000, 3_000))
                .isFalse();
    }

    @Test
    void shouldRecoverWhenWindowChanges() {
        assertThat(cacheManager.checkAndIncrementUserCallCount("u1", "tool", 1, 60_000, 59_000))
                .isTrue();
        assertThat(cacheManager.checkAndIncrementUserCallCount("u1", "tool", 1, 60_000, 59_999))
                .isFalse();
        assertThat(cacheManager.checkAndIncrementUserCallCount("u1", "tool", 1, 60_000, 60_000))
                .isTrue();
    }
}
