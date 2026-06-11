package com.hmdp.ai.fallback;

import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.impl.AIFallbackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
@Slf4j
public class FallbackPolicy {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long FAILURE_RESET_TIME = 300000L;

    @Resource
    private AIFallbackService aiFallbackService;

    @Resource
    private AiMetricsService aiMetricsService;

    private final Map<String, AtomicInteger> failureCounters = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFailureTime = new ConcurrentHashMap<>();

    public boolean shouldUseFallback(String serviceType) {
        AtomicInteger counter = failureCounters.get(serviceType);
        int failureCount = counter == null ? 0 : counter.get();
        Long lastFailure = lastFailureTime.get(serviceType);
        if (failureCount >= FAILURE_THRESHOLD) {
            long currentTime = System.currentTimeMillis();
            if (lastFailure != null && (currentTime - lastFailure) < FAILURE_RESET_TIME) {
                return true;
            }
            if (counter != null) {
                counter.set(0);
            }
            lastFailureTime.remove(serviceType);
        }
        return false;
    }

    public void recordFailure(String serviceType) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastFailureTime.get(serviceType);
        if (lastTime == null || (currentTime - lastTime) > FAILURE_RESET_TIME) {
            failureCounters.computeIfAbsent(serviceType, ignored -> new AtomicInteger()).set(1);
        } else {
            failureCounters.computeIfAbsent(serviceType, ignored -> new AtomicInteger()).incrementAndGet();
        }
        lastFailureTime.put(serviceType, currentTime);
    }

    public ShopAIAnalysisResult fallbackAnalysis(Long shopId, String analysisType, boolean degraded) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        String summary = aiFallbackService.generateSummaryFallback(shopId);
        return ShopAIAnalysisResult.builder()
                .summary(summary)
                .sentiment("neutral")
                .keywords(parseKeywords(aiFallbackService.extractKeywordsFallback(summary)))
                .pros(Collections.emptyList())
                .cons(Collections.emptyList())
                .confidence(0.35)
                .evidenceIds(Collections.emptyList())
                .degraded(degraded)
                .build();
    }

    public String fallbackText(String memoryId, String prompt, String analysisType) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        return aiFallbackService.analyzeShopDataFallback(memoryId, prompt);
    }

    public String fallbackSummary(Long shopId, String analysisType) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        return aiFallbackService.generateSummaryFallback(shopId);
    }

    private List<String> parseKeywords(String keywordsStr) {
        if (keywordsStr == null || keywordsStr.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(keywordsStr.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(5)
                .collect(Collectors.toList());
    }
}
