package com.hmdp.ai.guard;

import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.EvidenceType;
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

    private EvidenceItem evidence(String id, Long shopId) {
        return EvidenceItem.builder()
                .id(id)
                .type(id.startsWith("shop_profile:") ? EvidenceType.SHOP_PROFILE : EvidenceType.REVIEW)
                .shopId(shopId)
                .snippet("证据")
                .build();
    }
}
