package com.hmdp.ai.infra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiTokenEstimatorTest {

    private final AiTokenEstimator estimator = new AiTokenEstimator();

    @Test
    void shouldEstimateChineseCharactersAsTokens() {
        assertThat(estimator.estimate("服务很好")).isEqualTo(4);
    }

    @Test
    void shouldEstimateAsciiWordsAsTokens() {
        assertThat(estimator.estimate("hello world qwen-plus")).isEqualTo(3);
    }

    @Test
    void shouldEstimateMixedText() {
        assertThat(estimator.estimate("服务 good!")).isEqualTo(4);
    }
}
