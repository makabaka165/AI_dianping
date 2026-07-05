package com.hmdp.controller;

import com.hmdp.ai.quota.AiQuotaExceededException;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.service.ai.AIService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AITestControllerTest {

    @Mock
    private AIService aiService;

    private TestableAITestController controller;

    @BeforeEach
    void setUp() {
        controller = new TestableAITestController();
        ReflectionTestUtils.setField(controller, "aiService", aiService);
    }

    @Test
    void healthCheckShouldReturnRateLimitedWhenQuotaExceeded() {
        controller.failOnQuotaCall = 1;
        controller.quotaException = new AiQuotaExceededException("quota exceeded");

        Result result = controller.healthCheck();

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.RATE_LIMITED.getCode());
        assertThat(result.getErrorMsg()).isEqualTo("quota exceeded");
        verify(aiService, never()).healthCheck(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchTestShouldConsumeQuotaForEachAiCall() {
        when(aiService.testChat(anyString())).thenReturn("chat");
        when(aiService.analyzeSentiment(anyString())).thenReturn("positive");
        when(aiService.extractKeywords(anyString())).thenReturn("keyword");
        when(aiService.testMemory(anyString(), anyString())).thenReturn("memory");
        when(aiService.testSummarize(anyString())).thenReturn("summary");

        Result result = controller.batchTest();

        assertThat(result.getSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) result.getData();
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertThat(summary.get("totalTests")).isEqualTo(5);
        assertThat(summary.get("successCount")).isEqualTo(5L);
        assertThat(summary.get("failureCount")).isEqualTo(0L);
        assertThat(summary.get("successRate")).isEqualTo("100.0%");
        assertThat(controller.quotaOperations).hasSize(6).containsOnly("ai-test-batch");
        verify(aiService).testChat(anyString());
        verify(aiService).analyzeSentiment(anyString());
        verify(aiService).extractKeywords(anyString());
        verify(aiService, times(2)).testMemory(anyString(), anyString());
        verify(aiService).testSummarize(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchTestSummaryShouldCountFailedChildResultsOnly() {
        when(aiService.testChat(anyString())).thenReturn("chat");
        when(aiService.analyzeSentiment(anyString())).thenReturn("positive");
        when(aiService.extractKeywords(anyString())).thenReturn("keyword");
        when(aiService.testMemory(anyString(), anyString())).thenReturn("memory");
        when(aiService.testSummarize(anyString())).thenThrow(new RuntimeException("AI unavailable"));

        Result result = controller.batchTest();

        assertThat(result.getSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) result.getData();
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertThat(summary.get("totalTests")).isEqualTo(5);
        assertThat(summary.get("successCount")).isEqualTo(4L);
        assertThat(summary.get("failureCount")).isEqualTo(1L);
        assertThat(summary.get("successRate")).isEqualTo("80.0%");
    }

    @Test
    void batchTestShouldReturnRateLimitedWhenQuotaExceeded() {
        when(aiService.testChat(anyString())).thenReturn("chat");
        controller.failOnQuotaCall = 2;
        controller.quotaException = new AiQuotaExceededException("quota exceeded");

        Result result = controller.batchTest();

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.RATE_LIMITED.getCode());
        assertThat(result.getErrorMsg()).isEqualTo("quota exceeded");
        assertThat(controller.quotaOperations).containsExactly("ai-test-batch", "ai-test-batch");
        verify(aiService).testChat(anyString());
        verify(aiService, never()).analyzeSentiment(anyString());
    }

    @Test
    void stressTestShouldConsumeQuotaForEachAiCall() {
        when(aiService.testChat(anyString())).thenReturn("ok");

        Result result = controller.stressTest(3);

        assertThat(result.getSuccess()).isTrue();
        assertThat(controller.quotaOperations).hasSize(3).containsOnly("ai-test-stress");
        verify(aiService, times(3)).testChat(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stressTestShouldReturnStableMaxQpsWhenAllAiCallsFail() {
        when(aiService.testChat(anyString())).thenThrow(new RuntimeException("AI unavailable"));

        Result result = controller.stressTest(3);

        assertThat(result.getSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertThat(data.get("successCount")).isEqualTo(0);
        assertThat(data.get("failureCount")).isEqualTo(3);
        assertThat(data.get("maxQPS")).isEqualTo("0.0");
        assertThat(data.get("maxQPS")).isNotIn("Infinity", "NaN");
        assertThat(controller.quotaOperations).hasSize(3).containsOnly("ai-test-stress");
        verify(aiService, times(3)).testChat(anyString());
    }

    @Test
    void stressTestShouldRejectNonPositiveCountWithoutCallingAiService() {
        assertStressTestRejectsWithoutAiCall(0);
        assertStressTestRejectsWithoutAiCall(-1);
        assertStressTestRejectsWithoutAiCall(null);

        assertThat(controller.quotaOperations).isEmpty();
        verify(aiService, never()).testChat(anyString());
    }

    @Test
    void stressTestShouldRejectCountAboveLimitWithoutCallingAiService() {
        Result result = controller.stressTest(21);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("20");
        assertThat(controller.quotaOperations).isEmpty();
        verify(aiService, never()).testChat(anyString());
    }

    @Test
    void stressTestShouldReturnServiceUnavailableWhenQuotaInfraFails() {
        controller.failOnQuotaCall = 1;
        controller.quotaException = AiQuotaExceededException.infra("quota unavailable", new RuntimeException("redis down"));

        Result result = controller.stressTest(3);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getCode());
        assertThat(result.getErrorMsg()).isEqualTo("quota unavailable");
        verify(aiService, never()).testChat(anyString());
    }

    private static class TestableAITestController extends AITestController {
        private final List<String> quotaOperations = new ArrayList<>();
        private int failOnQuotaCall = -1;
        private AiQuotaExceededException quotaException = new AiQuotaExceededException("quota exceeded");

        @Override
        protected void checkQuota(String operation) {
            quotaOperations.add(operation);
            if (quotaOperations.size() == failOnQuotaCall) {
                throw quotaException;
            }
        }
    }

    private void assertStressTestRejectsWithoutAiCall(Integer count) {
        Result result = controller.stressTest(count);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("greater than 0");
    }
}
