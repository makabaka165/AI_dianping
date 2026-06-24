package com.hmdp.ai.infra;

import org.springframework.stereotype.Component;

@Component
public class DocumentIndexDecisionService {

    public DocumentIndexDecision decide(DocumentQualityAssessment assessment,
                                        DocumentIndexPolicy policy,
                                        double minScore) {
        DocumentIndexPolicy safePolicy = policy == null ? DocumentIndexPolicy.OBSERVE_ONLY : policy;
        double safeMinScore = Math.max(0.0, Math.min(1.0, minScore));
        boolean lowQuality = assessment == null || assessment.getScore() < safeMinScore;

        DocumentIndexAction action;
        String reason;
        if (safePolicy == DocumentIndexPolicy.SKIP_LOW_QUALITY && lowQuality) {
            action = DocumentIndexAction.SKIP;
            reason = "LOW_QUALITY_SKIPPED";
        } else if (safePolicy == DocumentIndexPolicy.TAG_LOW_QUALITY && lowQuality) {
            action = DocumentIndexAction.INDEX_WITH_TAG;
            reason = "LOW_QUALITY_TAGGED";
        } else if (safePolicy == DocumentIndexPolicy.DEGRADE_LOW_QUALITY && lowQuality) {
            action = DocumentIndexAction.INDEX_WITH_DEGRADE;
            reason = "LOW_QUALITY_DEGRADED";
        } else if (safePolicy == DocumentIndexPolicy.SAMPLE_REVIEW && lowQuality) {
            action = DocumentIndexAction.INDEX_WITH_TAG;
            reason = "LOW_QUALITY_SAMPLED";
        } else {
            action = DocumentIndexAction.INDEX;
            reason = safePolicy == DocumentIndexPolicy.OBSERVE_ONLY ? "OBSERVED_ONLY" : "QUALITY_ACCEPTED";
        }

        return DocumentIndexDecision.builder()
                .policy(safePolicy)
                .action(action)
                .minScore(safeMinScore)
                .reason(reason)
                .assessment(assessment)
                .build();
    }
}
