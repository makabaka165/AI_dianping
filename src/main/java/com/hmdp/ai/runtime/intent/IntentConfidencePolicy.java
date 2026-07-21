package com.hmdp.ai.runtime.intent;

import org.springframework.stereotype.Component;

@Component
public class IntentConfidencePolicy {
  private static final double RULE_DIRECT_THRESHOLD = 0.85;
  private static final double CLARIFICATION_THRESHOLD = 0.60;

  public boolean shouldUseModel(double ruleConfidence) {
    return ruleConfidence < RULE_DIRECT_THRESHOLD;
  }

  public boolean requiresClarification(double confidence) {
    return confidence < CLARIFICATION_THRESHOLD;
  }
}
