package com.hmdp.ai.runtime.intent;

import com.hmdp.ai.runtime.node.NodeExecutionContext;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class IntentFusionService {
  private final IntentRuleClassifier rules;
  private final IntentModelClassifier model;
  private final IntentConfidencePolicy confidencePolicy;
  private final SlotFillingService slots;

  public IntentFusionService(
      IntentRuleClassifier rules,
      IntentModelClassifier model,
      IntentConfidencePolicy confidencePolicy,
      SlotFillingService slots) {
    this.rules = rules;
    this.model = model;
    this.confidencePolicy = confidencePolicy;
    this.slots = slots;
  }

  public IntentClassification classify(
      String text, Map<String, Object> entities, NodeExecutionContext context) {
    IntentClassification rule = rules.classify(text, entities);
    IntentClassification selected = rule;
    if (confidencePolicy.shouldUseModel(rule.getConfidence())) {
      Optional<IntentClassification> modelResult =
          model.classify(
              text,
              entities,
              context.getExecutionContext(),
              context.getAgent(),
              context.getNodeRunId());
      if (modelResult.isPresent() && modelResult.get().getConfidence() > rule.getConfidence()) {
        selected = modelResult.get();
      }
    }
    return slots.fill(selected, entities, context.getVariables());
  }
}
