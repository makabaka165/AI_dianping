package com.hmdp.ai.infra;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class DocumentIndexDecision {
    DocumentIndexPolicy policy;
    DocumentIndexAction action;
    double minScore;
    String reason;
    DocumentQualityAssessment assessment;

    public boolean shouldIndex() {
        return action != DocumentIndexAction.SKIP;
    }

    public boolean lowQuality() {
        return assessment == null || assessment.getScore() < minScore;
    }
}
