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
        QualityCheck detailCheck = validateFragments(result.getKeywords(), analysisType);
        if (!detailCheck.pass()) {
            return detailCheck;
        }
        detailCheck = validateFragments(result.getPros(), analysisType);
        if (!detailCheck.pass()) {
            return detailCheck;
        }
        detailCheck = validateFragments(result.getCons(), analysisType);
        if (!detailCheck.pass()) {
            return detailCheck;
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

    public QualityCheck validateQA(ShopQAResult result,
                                   Long expectedShopId,
                                   List<EvidenceItem> evidence,
                                   String analysisType) {
        if (result == null || expectedShopId == null || !expectedShopId.equals(result.getShopId())) {
            return reject(analysisType, "问答结果 shopId 与请求不一致");
        }
        return validateQA(result, evidence, analysisType);
    }

    public QualityCheck validateCompare(ShopCompareResult result,
                                        Long shopId1,
                                        Long shopId2,
                                        List<EvidenceItem> evidence,
                                        String analysisType) {
        if (result == null) {
            return reject(analysisType, "对比结果为空");
        }
        if (shopId1 == null || shopId2 == null) {
            return reject(analysisType, "对比请求 shopId 不能为空");
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
        QualityCheck detailCheck = validateFragments(result.getShop1Pros(), analysisType);
        if (!detailCheck.pass()) {
            return detailCheck;
        }
        detailCheck = validateFragments(result.getShop2Pros(), analysisType);
        if (!detailCheck.pass()) {
            return detailCheck;
        }
        detailCheck = validateFragments(result.getRiskNotes(), analysisType);
        if (!detailCheck.pass()) {
            return detailCheck;
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
        QualityCheck messageCheck = result.safeItems().isEmpty()
                ? validateText(result.getMessage(), analysisType)
                : validateOptionalFragment(result.getMessage(), analysisType);
        if (!messageCheck.pass()) {
            return messageCheck;
        }
        Set<Long> allowedCandidateShopIds = candidateShopIds == null ? Set.of() : candidateShopIds;
        Set<Integer> ranks = new HashSet<>();
        Set<Long> recommendedShopIds = new HashSet<>();
        for (ShopRecommendationItem item : result.safeItems()) {
            if (item == null) {
                return reject(analysisType, "推荐项为空");
            }
            if (item.getShopId() == null || !allowedCandidateShopIds.contains(item.getShopId())) {
                return reject(analysisType, "推荐项 shopId 不在候选店铺中");
            }
            if (!recommendedShopIds.add(item.getShopId())) {
                return reject(analysisType, "推荐项 shopId 重复");
            }
            if (item.getRank() == null || item.getRank() <= 0 || !ranks.add(item.getRank())) {
                return reject(analysisType, "推荐 rank 非法或重复");
            }
            if (isBlank(item.getReason())) {
                return reject(analysisType, "推荐理由为空");
            }
            QualityCheck evidenceCheck = validateRecommendationEvidenceIds(
                    item.safeEvidenceIds(), evidence, item.getShopId(), analysisType);
            if (!evidenceCheck.pass()) {
                return evidenceCheck;
            }
            QualityCheck reasonCheck = validateFragment(item.getReason(), analysisType);
            if (!reasonCheck.pass()) {
                return reasonCheck;
            }
            QualityCheck suitableForCheck = validateOptionalFragment(item.getSuitableFor(), analysisType);
            if (!suitableForCheck.pass()) {
                return suitableForCheck;
            }
            QualityCheck uncertaintyCheck = validateOptionalFragment(item.getUncertainty(), analysisType);
            if (!uncertaintyCheck.pass()) {
                return uncertaintyCheck;
            }
        }
        return QualityCheck.builder().decision(QualityDecision.PASS).build();
    }

    private QualityCheck validateOptionalFragment(String content, String analysisType) {
        if (isBlank(content)) {
            return QualityCheck.builder().decision(QualityDecision.PASS).build();
        }
        return validateFragment(content, analysisType);
    }

    private QualityCheck validateFragments(List<String> contents, String analysisType) {
        if (contents == null || contents.isEmpty()) {
            return QualityCheck.builder().decision(QualityDecision.PASS).build();
        }
        for (String content : contents) {
            if (isBlank(content)) {
                return reject(analysisType, "用户可见文本片段为空");
            }
            QualityCheck check = validateFragment(content, analysisType);
            if (!check.pass()) {
                return check;
            }
        }
        return QualityCheck.builder().decision(QualityDecision.PASS).build();
    }

    private QualityCheck validateFragment(String content, String analysisType) {
        AIResultQualityService.QualityCheckResult result = aiResultQualityService.validateContentFragment(content);
        if (result.isValid()) {
            return QualityCheck.builder().decision(QualityDecision.PASS).build();
        }
        return reject(analysisType, result.getReason());
    }

    private QualityCheck validateRecommendationEvidenceIds(List<String> evidenceIds,
                                                            List<EvidenceItem> evidence,
                                                            Long shopId,
                                                            String analysisType) {
        QualityCheck evidenceCheck = validateEvidenceIds(evidenceIds, evidence, analysisType);
        if (!evidenceCheck.pass() || evidenceIds == null || evidenceIds.isEmpty()) {
            return evidenceCheck;
        }
        Set<String> shopEvidenceIds = evidence == null ? Set.of() : evidence.stream()
                .filter(item -> item != null && shopId.equals(item.getShopId()))
                .map(EvidenceItem::getId)
                .filter(id -> id != null && !id.trim().isEmpty())
                .collect(Collectors.toSet());
        if (evidenceIds.stream().anyMatch(id -> !shopEvidenceIds.contains(id))) {
            return reject(analysisType, "推荐项 evidenceIds 与 shopId 不一致");
        }
        return QualityCheck.builder().decision(QualityDecision.PASS).build();
    }

    private QualityCheck validateEvidenceIds(List<String> evidenceIds,
                                             List<EvidenceItem> evidence,
                                             String analysisType) {
        Set<String> allowedIds = evidence == null ? Set.of() : evidence.stream()
                .filter(item -> item != null)
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
