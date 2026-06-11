package com.hmdp.ai.guard;

import com.hmdp.dto.ai.ReviewEvidence;
import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.service.AIResultQualityService;
import com.hmdp.service.AiMetricsService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
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
        aiMetricsService.increment("ai.quality.reject", analysisType, false);
        return QualityCheck.builder()
                .decision(QualityDecision.FALLBACK)
                .reason(result.getReason())
                .build();
    }

    public String postProcess(String content) {
        return aiResultQualityService.postProcessContent(content);
    }

    public QualityCheck validateAnalysis(ShopAIAnalysisResult result,
                                         List<ReviewEvidence> evidence,
                                         String analysisType) {
        if (result == null) {
            aiMetricsService.increment("ai.quality.reject", analysisType, false);
            return QualityCheck.builder().decision(QualityDecision.FALLBACK).reason("结构化结果为空").build();
        }
        if (result.getSummary() == null || result.getSummary().trim().length() < 10) {
            aiMetricsService.increment("ai.quality.reject", analysisType, false);
            return QualityCheck.builder().decision(QualityDecision.FALLBACK).reason("summary为空或过短").build();
        }
        if (!Arrays.asList("positive", "negative", "neutral").contains(result.getSentiment())) {
            aiMetricsService.increment("ai.quality.reject", analysisType, false);
            return QualityCheck.builder().decision(QualityDecision.FALLBACK).reason("sentiment枚举非法").build();
        }
        Set<Long> allowedIds = evidence.stream()
                .map(ReviewEvidence::getBlogId)
                .collect(Collectors.toSet());
        boolean invalidEvidenceId = result.safeEvidenceIds().stream()
                .anyMatch(id -> !allowedIds.contains(id));
        if (invalidEvidenceId) {
            aiMetricsService.increment("ai.quality.reject", analysisType, false);
            return QualityCheck.builder().decision(QualityDecision.FALLBACK).reason("evidenceIds不在本次证据中").build();
        }
        QualityCheck textCheck = validateText(result.getSummary(), analysisType);
        if (!textCheck.pass()) {
            return textCheck;
        }
        return QualityCheck.builder().decision(QualityDecision.PASS).build();
    }
}
