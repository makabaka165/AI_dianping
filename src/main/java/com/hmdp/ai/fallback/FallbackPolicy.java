package com.hmdp.ai.fallback;

import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopChatResult;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopRecommendationItem;
import com.hmdp.dto.ai.ShopView;
import com.hmdp.ai.infra.AiMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class FallbackPolicy {

    @Resource
    private AIFallbackService aiFallbackService;

    @Resource
    private AiMetricsService aiMetricsService;

    public ShopAIAnalysisResult fallbackAnalysis(Long shopId, String analysisType, boolean degraded) {
        return fallbackAnalysis(shopId, analysisType, degraded, FallbackReason.MODEL_UNAVAILABLE);
    }

    public ShopAIAnalysisResult fallbackAnalysis(Long shopId, String analysisType, boolean degraded, FallbackReason reason) {
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
                .fallbackReason(reason == null ? null : reason.name())
                .build();
    }

    public String fallbackText(String memoryId, String prompt, String analysisType) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        return aiFallbackService.analyzeShopDataFallback(memoryId, prompt);
    }

    public ShopQAResult fallbackQA(Long shopId, String question, String analysisType) {
        return fallbackQA(shopId, question, analysisType, FallbackReason.MODEL_UNAVAILABLE);
    }

    public ShopQAResult fallbackQA(Long shopId, String question, String analysisType, FallbackReason reason) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        return ShopQAResult.builder()
                .shopId(shopId)
                .question(question)
                .answer(qaMessage(reason))
                .evidenceIds(Collections.emptyList())
                .insufficientEvidence(true)
                .build();
    }

    public ShopCompareResult fallbackCompare(Long shopId1, Long shopId2, String aspect, String analysisType) {
        return fallbackCompare(shopId1, shopId2, aspect, analysisType, FallbackReason.MODEL_UNAVAILABLE);
    }

    public ShopCompareResult fallbackCompare(Long shopId1, Long shopId2, String aspect, String analysisType, FallbackReason reason) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        return ShopCompareResult.builder()
                .shopId1(shopId1)
                .shopId2(shopId2)
                .aspect(aspect)
                .conclusion(compareMessage(reason))
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
                                                 List<ShopView> candidates,
                                                 int limit,
                                                 String analysisType) {
        return fallbackRecommend(userPreference, category, candidates, limit, analysisType, FallbackReason.MODEL_UNAVAILABLE);
    }

    public ShopRecommendResult fallbackRecommend(String userPreference,
                                                 String category,
                                                 List<ShopView> candidates,
                                                 int limit,
                                                 String analysisType,
                                                 FallbackReason reason) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        List<ShopRecommendationItem> items = candidates == null ? Collections.emptyList() : candidates.stream()
                .limit(Math.max(1, Math.min(10, limit)))
                .map(shop -> ShopRecommendationItem.builder()
                        .rank(candidates.indexOf(shop) + 1)
                        .shopId(shop.getId())
                        .shopName(shop.getName())
                        .address(shop.getAddress())
                        .reason(recommendItemReason(reason))
                        .suitableFor("需要先浏览候选店铺公开信息的用户")
                        .uncertainty("缺少模型分析，推荐理由可信度较低。")
                        .evidenceIds(Collections.emptyList())
                        .confidence(0.3)
                        .build())
                .collect(Collectors.toList());
        return ShopRecommendResult.builder()
                .userPreference(userPreference)
                .category(category)
                .message(items.isEmpty() ? "当前候选店铺数据不足，无法给出可靠推荐。" : recommendMessage(reason))
                .items(items)
                .build();
    }

    public ShopChatResult fallbackChat(String message, String analysisType) {
        return fallbackChat(message, analysisType, FallbackReason.MODEL_UNAVAILABLE);
    }

    public ShopChatResult fallbackChat(String message, String analysisType, FallbackReason reason) {
        aiMetricsService.increment("ai.fallback.count", analysisType, true);
        return ShopChatResult.builder()
                .message(message == null || message.trim().isEmpty()
                        ? chatEmptyMessage(reason)
                        : chatMessage(reason))
                .clarification(false)
                .build();
    }

    private String qaMessage(FallbackReason reason) {
        if (reason == FallbackReason.QUALITY_REJECTED) {
            return "当前回答未通过质量校验，无法可靠回答该店铺问题。请换个问法或稍后重试。";
        }
        if (reason == FallbackReason.INSUFFICIENT_EVIDENCE) {
            return "当前评价证据不足，无法可靠回答该店铺问题。";
        }
        return "当前 AI 服务不可用，无法可靠回答该店铺问题。请稍后重试。";
    }

    private String compareMessage(FallbackReason reason) {
        if (reason == FallbackReason.QUALITY_REJECTED) {
            return "当前对比结果未通过质量校验，无法基于证据给出可靠结论。";
        }
        if (reason == FallbackReason.INSUFFICIENT_EVIDENCE) {
            return "当前评价证据不足，无法可靠判断两家店铺的对比表现。";
        }
        return "当前 AI 服务不可用，无法基于证据给出可靠对比结论。";
    }

    private String recommendMessage(FallbackReason reason) {
        if (reason == FallbackReason.QUALITY_REJECTED) {
            return "AI 推荐结果未通过质量校验，以下仅按候选店铺公开信息保守返回。";
        }
        return "AI 降级推荐，仅供参考。";
    }

    private String recommendItemReason(FallbackReason reason) {
        if (reason == FallbackReason.QUALITY_REJECTED) {
            return "模型推荐结果未通过质量校验，仅按候选店铺公开热度信息保守返回。";
        }
        return "AI 服务不可用，仅按候选店铺公开热度信息保守返回。";
    }

    private String chatEmptyMessage(FallbackReason reason) {
        if (reason == FallbackReason.QUALITY_REJECTED) {
            return "当前回答未通过质量校验，请换个问法再试。";
        }
        return "当前 AI 服务不可用，请稍后重试。";
    }

    private String chatMessage(FallbackReason reason) {
        if (reason == FallbackReason.QUALITY_REJECTED) {
            return "当前回答未通过质量校验。我可以帮你做店铺总结、评价问答、店铺对比和推荐，也可以回答平台共性规则。";
        }
        return "当前 AI 服务不可用。我可以在恢复后帮你做店铺总结、评价问答、店铺对比和推荐。";
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
