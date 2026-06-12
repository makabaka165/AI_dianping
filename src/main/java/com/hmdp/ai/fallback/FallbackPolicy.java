package com.hmdp.ai.fallback;

import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.impl.AIFallbackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Slf4j
public class FallbackPolicy {

    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration FAILURE_WINDOW = Duration.ofSeconds(60);
    private static final Duration OPEN_DURATION = Duration.ofSeconds(30);

    @Resource
    private AIFallbackService aiFallbackService;

    @Resource
    private AiMetricsService aiMetricsService;

    private final Map<String, FailureWindow> failureWindows = new ConcurrentHashMap<>();

    public boolean shouldUseFallback(String serviceType) {
        FailureWindow window = failureWindows.get(normalize(serviceType));
        return window != null && window.shouldUseFallback(System.currentTimeMillis());
    }

    public void recordFailure(String serviceType) {
        String key = normalize(serviceType);
        boolean opened = failureWindows
                .computeIfAbsent(key, ignored -> new FailureWindow())
                .recordFailure(System.currentTimeMillis());
        if (opened) {
            log.warn("AI fallback window opened, serviceType={}", key);
        } else {
            log.debug("AI model failure recorded by workflow, serviceType={}", key);
        }
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

    private String normalize(String serviceType) {
        return serviceType == null || serviceType.trim().isEmpty() ? "default" : serviceType.trim();
    }

    private static class FailureWindow {
        private final Deque<Long> failures = new ArrayDeque<>();
        private long openUntil;

        synchronized boolean recordFailure(long now) {
            purge(now);
            failures.addLast(now);
            if (failures.size() >= FAILURE_THRESHOLD) {
                openUntil = now + OPEN_DURATION.toMillis();
                failures.clear();
                return true;
            }
            return false;
        }

        synchronized boolean shouldUseFallback(long now) {
            if (openUntil > now) {
                return true;
            }
            if (openUntil > 0) {
                openUntil = 0;
            }
            purge(now);
            return false;
        }

        private void purge(long now) {
            long threshold = now - FAILURE_WINDOW.toMillis();
            while (!failures.isEmpty() && failures.peekFirst() < threshold) {
                failures.removeFirst();
            }
        }
    }
}
