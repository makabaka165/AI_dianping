package com.hmdp.ai.guard;

import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.EvidenceType;
import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopRecommendationItem;
import com.hmdp.ai.infra.AIResultQualityService;
import com.hmdp.ai.infra.AiMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QualityGuardTest {

    @Mock
    private AIResultQualityService aiResultQualityService;
    @Mock
    private AiMetricsService aiMetricsService;

    private QualityGuard qualityGuard;

    @BeforeEach
    void setUp() {
        qualityGuard = new QualityGuard();
        ReflectionTestUtils.setField(qualityGuard, "aiResultQualityService", aiResultQualityService);
        ReflectionTestUtils.setField(qualityGuard, "aiMetricsService", aiMetricsService);
        AIResultQualityService.QualityCheckResult valid = new AIResultQualityService.QualityCheckResult();
        valid.setValid(true);
        lenient().when(aiResultQualityService.validateContent(anyString())).thenReturn(valid);
        lenient().when(aiResultQualityService.validateContentFragment(anyString())).thenReturn(valid);
    }

    @Test
    void validateQAShouldRejectEvidenceIdsOutsideContext() {
        ShopQAResult result = ShopQAResult.builder()
                .shopId(1L)
                .question("服务")
                .answer("服务表现较稳定")
                .evidenceIds(List.of("review:99"))
                .insufficientEvidence(false)
                .build();

        QualityCheck check = qualityGuard.validateQA(result, List.of(evidence("review:1", 1L)), "ask");

        assertThat(check.pass()).isFalse();
        assertThat(check.getReason()).contains("evidenceIds");
    }

    @Test
    void validateQAShouldRejectShopIdOutsideRequestContext() {
        ShopQAResult result = ShopQAResult.builder()
                .shopId(2L)
                .question("service")
                .answer("Service quality is consistently stable.")
                .evidenceIds(List.of())
                .insufficientEvidence(false)
                .build();

        QualityCheck check = qualityGuard.validateQA(result, 1L, List.of(), "ask");

        assertThat(check.pass()).isFalse();
        assertThat(check.getReason()).contains("shopId");
    }

    @Test
    void validateCompareShouldRejectInvalidWinnerEnum() {
        ShopCompareResult result = ShopCompareResult.builder()
                .shopId1(1L)
                .shopId2(2L)
                .aspect("服务")
                .conclusion("服务差异明显")
                .winnerByAspect("UNKNOWN")
                .shop1Score(80)
                .shop2Score(60)
                .evidenceIds(List.of("review:1"))
                .build();

        QualityCheck check = qualityGuard.validateCompare(result, 1L, 2L, List.of(evidence("review:1", 1L)), "compare");

        assertThat(check.pass()).isFalse();
        assertThat(check.getReason()).contains("winnerByAspect");
    }

    @Test
    void validateCompareShouldRejectMissingRequestShopId() {
        ShopCompareResult result = ShopCompareResult.builder()
                .shopId1(1L)
                .shopId2(2L)
                .aspect("service")
                .conclusion("Service quality differs between the two shops.")
                .winnerByAspect(ShopCompareResult.SHOP_1)
                .shop1Score(80)
                .shop2Score(60)
                .evidenceIds(List.of())
                .build();

        QualityCheck check = qualityGuard.validateCompare(result, null, 2L, List.of(), "compare");

        assertThat(check.pass()).isFalse();
        assertThat(check.getReason()).contains("shopId");
    }

    @Test
    void validateCompareShouldRejectUnsafeDetailText() {
        String unsafeText = "绝对最好";
        rejectFragment(unsafeText);
        ShopCompareResult result = ShopCompareResult.builder()
                .shopId1(1L)
                .shopId2(2L)
                .aspect("service")
                .conclusion("Service quality differs between the two shops.")
                .winnerByAspect(ShopCompareResult.SHOP_1)
                .shop1Score(80)
                .shop2Score(60)
                .shop1Pros(List.of(unsafeText))
                .evidenceIds(List.of())
                .build();

        QualityCheck check = qualityGuard.validateCompare(result, 1L, 2L, List.of(), "compare");

        assertThat(check.pass()).isFalse();
        assertThat(check.getReason()).contains("过度确定");
    }

    @Test
    void validateAnalysisShouldRejectUnsafeDetailText() {
        String unsafeText = "违法犯罪指导";
        rejectFragment(unsafeText);
        ShopAIAnalysisResult result = ShopAIAnalysisResult.builder()
                .summary("The available evidence indicates generally stable service.")
                .sentiment("neutral")
                .keywords(List.of(unsafeText))
                .evidenceIds(List.of())
                .build();

        QualityCheck check = qualityGuard.validateAnalysis(result, List.of(), "summary");

        assertThat(check.pass()).isFalse();
        assertThat(check.getReason()).contains("不合规");
    }

    @Test
    void validateRecommendShouldRejectShopOutsideCandidates() {
        ShopRecommendResult result = ShopRecommendResult.builder()
                .userPreference("约会")
                .category("餐厅")
                .items(List.of(ShopRecommendationItem.builder()
                        .rank(1)
                        .shopId(99L)
                        .reason("安静")
                        .evidenceIds(List.of("shop_profile:99"))
                        .build()))
                .build();

        QualityCheck check = qualityGuard.validateRecommend(result, Set.of(1L),
                List.of(evidence("shop_profile:99", 99L)), "recommend");

        assertThat(check.pass()).isFalse();
        assertThat(check.getReason()).contains("候选店铺");
    }

    @Test
    void validateRecommendShouldTreatMissingCandidatesAsEmpty() {
        ShopRecommendResult result = recommendResult(null,
                "This shop has a quiet setting suitable for conversation.", null, null);

        QualityCheck check = qualityGuard.validateRecommend(result, null, List.of(), "recommend");

        assertThat(check.pass()).isFalse();
        assertThat(check.getReason()).contains("候选店铺");
    }

    @Test
    void validateRecommendShouldRejectDuplicateShopIds() {
        ShopRecommendResult result = ShopRecommendResult.builder()
                .userPreference("conversation")
                .category("restaurant")
                .items(List.of(
                        recommendationItem(1, 1L, List.of()),
                        recommendationItem(2, 1L, List.of())))
                .build();

        QualityCheck check = qualityGuard.validateRecommend(result, Set.of(1L), List.of(), "recommend");

        assertThat(check.pass()).isFalse();
        assertThat(check.getReason()).contains("shopId 重复");
    }

    @Test
    void validateRecommendShouldRejectEvidenceFromAnotherShop() {
        ShopRecommendResult result = ShopRecommendResult.builder()
                .userPreference("conversation")
                .category("restaurant")
                .items(List.of(recommendationItem(1, 1L, List.of("review:2"))))
                .build();

        QualityCheck check = qualityGuard.validateRecommend(
                result, Set.of(1L), List.of(evidence("review:2", 2L)), "recommend");

        assertThat(check.pass()).isFalse();
        assertThat(check.getReason()).contains("evidenceIds").contains("shopId");
    }

    @Test
    void validateRecommendShouldRejectUnsafeUserVisibleText() {
        String unsafeText = "我保证这家店绝对最好";
        AIResultQualityService.QualityCheckResult invalid = new AIResultQualityService.QualityCheckResult();
        invalid.setValid(false);
        invalid.setReason("内容包含过度确定或越界承诺");
        when(aiResultQualityService.validateContentFragment(unsafeText)).thenReturn(invalid);

        QualityCheck messageCheck = qualityGuard.validateRecommend(
                recommendResult(unsafeText, "This shop has a quiet setting suitable for conversation.", null, null),
                Set.of(1L), List.of(), "recommend");
        QualityCheck reasonCheck = qualityGuard.validateRecommend(
                recommendResult(null, unsafeText, null, null),
                Set.of(1L), List.of(), "recommend");
        QualityCheck suitableForCheck = qualityGuard.validateRecommend(
                recommendResult(null, "This shop has a quiet setting suitable for conversation.", unsafeText, null),
                Set.of(1L), List.of(), "recommend");
        QualityCheck uncertaintyCheck = qualityGuard.validateRecommend(
                recommendResult(null, "This shop has a quiet setting suitable for conversation.", null, unsafeText),
                Set.of(1L), List.of(), "recommend");

        assertThat(messageCheck.pass()).as("message").isFalse();
        assertThat(reasonCheck.pass()).as("reason").isFalse();
        assertThat(suitableForCheck.pass()).as("suitableFor").isFalse();
        assertThat(uncertaintyCheck.pass()).as("uncertainty").isFalse();
    }

    private ShopRecommendResult recommendResult(String message,
                                                  String reason,
                                                  String suitableFor,
                                                  String uncertainty) {
        return ShopRecommendResult.builder()
                .userPreference("conversation")
                .category("restaurant")
                .message(message)
                .items(List.of(ShopRecommendationItem.builder()
                        .rank(1)
                        .shopId(1L)
                        .reason(reason)
                        .suitableFor(suitableFor)
                        .uncertainty(uncertainty)
                        .evidenceIds(List.of())
                        .build()))
                .build();
    }

    private ShopRecommendationItem recommendationItem(int rank, long shopId, List<String> evidenceIds) {
        return ShopRecommendationItem.builder()
                .rank(rank)
                .shopId(shopId)
                .reason("This shop has a quiet setting suitable for conversation.")
                .evidenceIds(evidenceIds)
                .build();
    }

    private void rejectFragment(String content) {
        AIResultQualityService.QualityCheckResult invalid = new AIResultQualityService.QualityCheckResult();
        invalid.setValid(false);
        invalid.setReason(content.contains("违法")
                ? "内容包含明显不合规表达"
                : "内容包含过度确定或越界承诺");
        when(aiResultQualityService.validateContentFragment(content)).thenReturn(invalid);
    }

    private EvidenceItem evidence(String id, Long shopId) {
        return EvidenceItem.builder()
                .id(id)
                .type(id.startsWith("shop_profile:") ? EvidenceType.SHOP_PROFILE : EvidenceType.REVIEW)
                .shopId(shopId)
                .snippet("证据")
                .build();
    }
}
