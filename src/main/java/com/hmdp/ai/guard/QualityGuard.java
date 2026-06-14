package com.hmdp.ai.guard;

import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopRecommendationItem;
import com.hmdp.ai.infra.AIResultQualityService;
import com.hmdp.ai.infra.AiMetricsService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class QualityGuard {

    @Resource
    private AIResultQualityService aiResultQualityService;

    @Resource
    private AiMetricsService aiMetricsService;

    public QualityCheck validateText(String content, String analysisType) {
        AIResultQualityService.QualityCheckResult result = aiResultQualityService.validateContent(content);
        if (result.isValid()) {
            return QualityCheck.builder().decision(QualityDecision.PASS).build();
        }
        return reject(analysisType, result.getReason());
    }

    public String postProcess(String content) {
        return aiResultQualityService.postProcessContent(content);
    }

    public QualityCheck validateAnalysis(ShopAIAnalysisResult result,
                                         List<EvidenceItem> evidence,
                                         String analysisType) {
        if (result == null) {
            return reject(analysisType, "结构化总结结果为空");
        }
        if (isBlank(result.getSummary()) || result.getSummary().trim().length() < 10) {
            return reject(analysisType, "summary 为空或过短");
        }
        if (!Arrays.asList("positive", "negative", "neutral").contains(result.getSentiment())) {
            return reject(analysisType, "sentiment 枚举非法");
        }
        QualityCheck evidenceCheck = validateEvidenceIds(result.safeEvidenceIds(), evidence, analysisType);
        if (!evidenceCheck.pass()) {
            return evidenceCheck;
        }
        QualityCheck textCheck = validateText(result.getSummary(), analysisType);
        if (!textCheck.pass()) {
            return textCheck;
        }
        return QualityCheck.builder().decision(QualityDecision.PASS).build();
    }

    public QualityCheck validateQA(ShopQAResult result,
                                   List<EvidenceItem> evidence,
                                   String analysisType) {
        if (result == null) {
            return reject(analysisType, "问答结果为空");
        }
        if (isBlank(result.getAnswer())) {
            return reject(analysisType, "answer 为空");
        }
        QualityCheck evidenceCheck = validateEvidenceIds(result.safeEvidenceIds(), evidence, analysisType);
        if (!evidenceCheck.pass()) {
            return evidenceCheck;
        }
        if (Boolean.TRUE.equals(result.getInsufficientEvidence()) && !result.safeEvidenceIds().isEmpty()) {
            return reject(analysisType, "证据不足回答不应引用证据");
        }
        QualityCheck textCheck = validateText(result.getAnswer(), analysisType);
        if (!textCheck.pass()) {
            return textCheck;
        }
        return QualityCheck.builder().decision(QualityDecision.PASS).build();
    }

    public QualityCheck validateCompare(ShopCompareResult result,
                                        Long shopId1,
                                        Long shopId2,
                                        List<EvidenceItem> evidence,
                                        String analysisType) {
        if (result == null) {
            return reject(analysisType, "对比结果为空");
        }
        if (!shopId1.equals(result.getShopId1()) || !shopId2.equals(result.getShopId2())) {
            return reject(analysisType, "对比结果 shopId 与请求不一致");
        }
        if (!Arrays.asList(ShopCompareResult.SHOP_1, ShopCompareResult.SHOP_2,
                ShopCompareResult.TIE, ShopCompareResult.INSUFFICIENT).contains(result.getWinnerByAspect())) {
            return reject(analysisType, "winnerByAspect 枚举非法");
        }
        if (!scoreValid(result.getShop1Score()) || !scoreValid(result.getShop2Score())) {
            return reject(analysisType, "对比分数必须在 0-100");
        }
        if (isBlank(result.getConclusion())) {
            return reject(analysisType, "conclusion 为空");
        }
        QualityCheck evidenceCheck = validateEvidenceIds(result.safeEvidenceIds(), evidence, analysisType);
        if (!evidenceCheck.pass()) {
            return evidenceCheck;
        }
        QualityCheck textCheck = validateText(result.getConclusion(), analysisType);
        if (!textCheck.pass()) {
            return textCheck;
        }
        return QualityCheck.builder().decision(QualityDecision.PASS).build();
    }

    public QualityCheck validateRecommend(ShopRecommendResult result,
                                          Set<Long> candidateShopIds,
                                          List<EvidenceItem> evidence,
                                          String analysisType) {
        if (result == null) {
            return reject(analysisType, "推荐结果为空");
        }
        if (result.safeItems().isEmpty() && isBlank(result.getMessage())) {
            return reject(analysisType, "推荐结果为空");
        }
        Set<Integer> ranks = new HashSet<>();
        for (ShopRecommendationItem item : result.safeItems()) {
            if (item.getShopId() == null || !candidateShopIds.contains(item.getShopId())) {
                return reject(analysisType, "推荐项 shopId 不在候选店铺中");
            }
            if (item.getRank() == null || item.getRank() <= 0 || !ranks.add(item.getRank())) {
                return reject(analysisType, "推荐 rank 非法或重复");
            }
            if (isBlank(item.getReason())) {
                return reject(analysisType, "推荐理由为空");
            }
            QualityCheck evidenceCheck = validateEvidenceIds(item.safeEvidenceIds(), evidence, analysisType);
            if (!evidenceCheck.pass()) {
                return evidenceCheck;
            }
        }
        return QualityCheck.builder().decision(QualityDecision.PASS).build();
    }

    private QualityCheck validateEvidenceIds(List<String> evidenceIds,
                                             List<EvidenceItem> evidence,
                                             String analysisType) {
        Set<String> allowedIds = evidence == null ? Set.of() : evidence.stream()
                .map(EvidenceItem::getId)
                .filter(id -> id != null && !id.trim().isEmpty())
                .collect(Collectors.toSet());
        boolean invalidEvidenceId = evidenceIds == null ? false : evidenceIds.stream()
                .anyMatch(id -> id == null || !allowedIds.contains(id));
        if (invalidEvidenceId) {
            return reject(analysisType, "evidenceIds 不在本次证据中");
        }
        return QualityCheck.builder().decision(QualityDecision.PASS).build();
    }

    private QualityCheck reject(String analysisType, String reason) {
        aiMetricsService.increment("ai.quality.reject", analysisType, false);
        return QualityCheck.builder()
                .decision(QualityDecision.FALLBACK)
                .reason(reason)
                .build();
    }

    private boolean scoreValid(Integer score) {
        return score != null && score >= 0 && score <= 100;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
