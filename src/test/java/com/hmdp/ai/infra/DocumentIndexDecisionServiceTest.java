package com.hmdp.ai.infra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentIndexDecisionServiceTest {

    private final DocumentIndexDecisionService service = new DocumentIndexDecisionService();

    @Test
    void observeOnlyShouldKeepIndexingLowQualityDocument() {
        DocumentIndexDecision decision = service.decide(
                assessment(0.30),
                DocumentIndexPolicy.OBSERVE_ONLY,
                0.45);

        assertThat(decision.shouldIndex()).isTrue();
        assertThat(decision.getAction()).isEqualTo(DocumentIndexAction.INDEX);
        assertThat(decision.getReason()).isEqualTo("OBSERVED_ONLY");
        assertThat(decision.lowQuality()).isTrue();
    }

    @Test
    void skipLowQualityShouldSkipLowQualityDocument() {
        DocumentIndexDecision decision = service.decide(
                assessment(0.30),
                DocumentIndexPolicy.SKIP_LOW_QUALITY,
                0.45);

        assertThat(decision.shouldIndex()).isFalse();
        assertThat(decision.getAction()).isEqualTo(DocumentIndexAction.SKIP);
        assertThat(decision.getReason()).isEqualTo("LOW_QUALITY_SKIPPED");
    }

    @Test
    void tagLowQualityShouldIndexWithTag() {
        DocumentIndexDecision decision = service.decide(
                assessment(0.30),
                DocumentIndexPolicy.TAG_LOW_QUALITY,
                0.45);

        assertThat(decision.shouldIndex()).isTrue();
        assertThat(decision.getAction()).isEqualTo(DocumentIndexAction.INDEX_WITH_TAG);
        assertThat(decision.getReason()).isEqualTo("LOW_QUALITY_TAGGED");
    }

    @Test
    void degradeLowQualityShouldIndexWithDegradeAction() {
        DocumentIndexDecision decision = service.decide(
                assessment(0.30),
                DocumentIndexPolicy.DEGRADE_LOW_QUALITY,
                0.45);

        assertThat(decision.shouldIndex()).isTrue();
        assertThat(decision.getAction()).isEqualTo(DocumentIndexAction.INDEX_WITH_DEGRADE);
        assertThat(decision.getReason()).isEqualTo("LOW_QUALITY_DEGRADED");
    }

    @Test
    void highQualityDocumentShouldIndexForEveryDeterministicPolicy() {
        for (DocumentIndexPolicy policy : new DocumentIndexPolicy[]{
                DocumentIndexPolicy.OBSERVE_ONLY,
                DocumentIndexPolicy.SKIP_LOW_QUALITY,
                DocumentIndexPolicy.TAG_LOW_QUALITY,
                DocumentIndexPolicy.DEGRADE_LOW_QUALITY
        }) {
            DocumentIndexDecision decision = service.decide(assessment(0.90), policy, 0.45);

            assertThat(decision.shouldIndex()).as("policy=%s", policy).isTrue();
            assertThat(decision.getAction()).as("policy=%s", policy).isEqualTo(DocumentIndexAction.INDEX);
            String expectedReason = policy == DocumentIndexPolicy.OBSERVE_ONLY ? "OBSERVED_ONLY" : "QUALITY_ACCEPTED";
            assertThat(decision.getReason()).as("policy=%s", policy).isEqualTo(expectedReason);
            assertThat(decision.lowQuality()).as("policy=%s", policy).isFalse();
        }
    }

    @Test
    void missingAssessmentShouldBeTreatedAsLowQualityForStrictPolicy() {
        DocumentIndexDecision decision = service.decide(
                null,
                DocumentIndexPolicy.SKIP_LOW_QUALITY,
                0.45);

        assertThat(decision.lowQuality()).isTrue();
        assertThat(decision.shouldIndex()).isFalse();
        assertThat(decision.getAction()).isEqualTo(DocumentIndexAction.SKIP);
        assertThat(decision.getReason()).isEqualTo("LOW_QUALITY_SKIPPED");
    }

    private DocumentQualityAssessment assessment(double score) {
        return DocumentQualityAssessment.builder()
                .profile(DocumentQualityProfile.SHOP_REVIEW)
                .level(DocumentQualityLevel.fromScore(score))
                .score(score)
                .build();
    }
}
