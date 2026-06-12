package com.hmdp.ai.fallback;

import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.impl.AIFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FallbackPolicyTest {

    @Mock
    private AIFallbackService aiFallbackService;

    @Mock
    private AiMetricsService aiMetricsService;

    private FallbackPolicy fallbackPolicy;

    @BeforeEach
    void setUp() {
        fallbackPolicy = new FallbackPolicy();
        ReflectionTestUtils.setField(fallbackPolicy, "aiFallbackService", aiFallbackService);
        ReflectionTestUtils.setField(fallbackPolicy, "aiMetricsService", aiMetricsService);
    }

    @Test
    void fallbackAnalysisShouldUseActualShopId() {
        when(aiFallbackService.generateSummaryFallback(99L)).thenReturn("fallback for 99");
        when(aiFallbackService.extractKeywordsFallback("fallback for 99")).thenReturn("服务,环境");

        ShopAIAnalysisResult result = fallbackPolicy.fallbackAnalysis(99L, "summary", true);

        assertThat(result.getSummary()).isEqualTo("fallback for 99");
        assertThat(result.getDegraded()).isTrue();
        verify(aiFallbackService).generateSummaryFallback(99L);
    }

    @Test
    void shouldOpenLocalFallbackWindowAfterRepeatedFailures() {
        fallbackPolicy.recordFailure("model");
        fallbackPolicy.recordFailure("model");
        fallbackPolicy.recordFailure("model");

        assertThat(fallbackPolicy.shouldUseFallback("model")).isTrue();
    }

    @Test
    void shouldKeepFailureWindowsPerServiceType() {
        fallbackPolicy.recordFailure("model-a");
        fallbackPolicy.recordFailure("model-a");
        fallbackPolicy.recordFailure("model-a");

        assertThat(fallbackPolicy.shouldUseFallback("model-a")).isTrue();
        assertThat(fallbackPolicy.shouldUseFallback("model-b")).isFalse();
    }
}
