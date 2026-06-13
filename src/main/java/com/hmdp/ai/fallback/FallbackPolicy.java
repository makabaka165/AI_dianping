package com.hmdp.ai.fallback;

import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopChatResult;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopRecommendationItem;
import com.hmdp.entity.Shop;
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

    public ShopQAResult fallbackQA(Long shopId, String question, String analysisType) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        return ShopQAResult.builder()
                .shopId(shopId)
                .question(question)
                .answer("当前 AI 服务不可用，无法可靠回答该店铺问题。请稍后重试。")
                .evidenceIds(Collections.emptyList())
                .insufficientEvidence(true)
                .build();
    }

    public ShopCompareResult fallbackCompare(Long shopId1, Long shopId2, String aspect, String analysisType) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        return ShopCompareResult.builder()
                .shopId1(shopId1)
                .shopId2(shopId2)
                .aspect(aspect)
                .conclusion("当前 AI 服务不可用，无法基于证据给出可靠对比结论。")
                .winnerByAspect(ShopCompareResult.INSUFFICIENT)
                .shop1Score(0)
                .shop2Score(0)
                .shop1Pros(Collections.emptyList())
                .shop2Pros(Collections.emptyList())
                .riskNotes(List.of("AI 降级结果，不应作为最终决策依据"))
                .evidenceIds(Collections.emptyList())
                .build();
    }

    public ShopRecommendResult fallbackRecommend(String userPreference,
                                                 String category,
                                                 List<Shop> candidates,
                                                 int limit,
                                                 String analysisType) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        List<ShopRecommendationItem> items = candidates == null ? Collections.emptyList() : candidates.stream()
                .limit(Math.max(1, Math.min(10, limit)))
                .map(shop -> ShopRecommendationItem.builder()
                        .rank(candidates.indexOf(shop) + 1)
                        .shopId(shop.getId())
                        .shopName(shop.getName())
                        .reason("AI 服务不可用，仅按候选店铺公开热度信息保守返回。")
                        .suitableFor("需要先浏览候选店铺公开信息的用户")
                        .uncertainty("缺少模型分析，推荐理由可信度较低。")
                        .evidenceIds(Collections.emptyList())
                        .confidence(0.3)
                        .build())
                .collect(Collectors.toList());
        return ShopRecommendResult.builder()
                .userPreference(userPreference)
                .category(category)
                .message(items.isEmpty() ? "当前候选店铺数据不足，无法给出可靠推荐。" : "AI 降级推荐，仅供参考。")
                .items(items)
                .build();
    }

    public ShopChatResult fallbackChat(String message, String analysisType) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        return ShopChatResult.builder()
                .message(message == null || message.trim().isEmpty()
                        ? "当前 AI 服务不可用，请稍后重试。"
                        : "当前 AI 服务不可用。我可以在恢复后帮你做店铺总结、评价问答、店铺对比和推荐。")
                .clarification(false)
                .build();
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
