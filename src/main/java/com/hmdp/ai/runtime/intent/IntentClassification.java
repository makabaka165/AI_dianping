package com.hmdp.ai.runtime.intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IntentClassification {
  private final String primaryIntent;
  private final List<String> secondaryIntents;
  private final double confidence;
  private final Map<String, Object> entities;
  private final List<String> missingSlots;
  private final boolean requiresClarification;

  public IntentClassification(
      String primaryIntent,
      List<String> secondaryIntents,
      double confidence,
      Map<String, Object> entities,
      List<String> missingSlots,
      boolean requiresClarification) {
    this.primaryIntent = primaryIntent;
    this.secondaryIntents =
        Collections.unmodifiableList(
            new ArrayList<>(secondaryIntents == null ? Collections.emptyList() : secondaryIntents));
    this.confidence = Math.max(0, Math.min(1, confidence));
    this.entities =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(entities == null ? Collections.emptyMap() : entities));
    this.missingSlots =
        Collections.unmodifiableList(
            new ArrayList<>(missingSlots == null ? Collections.emptyList() : missingSlots));
    this.requiresClarification = requiresClarification;
  }

  public IntentClassification withSlots(
      Map<String, Object> resolvedEntities, List<String> missing, boolean clarification) {
    return new IntentClassification(
        primaryIntent, secondaryIntents, confidence, resolvedEntities, missing, clarification);
  }

  public String getPrimaryIntent() {
    return primaryIntent;
  }

  public List<String> getSecondaryIntents() {
    return secondaryIntents;
  }

  public double getConfidence() {
    return confidence;
  }

  public Map<String, Object> getEntities() {
    return entities;
  }

  public List<String> getMissingSlots() {
    return missingSlots;
  }

  public boolean isRequiresClarification() {
    return requiresClarification;
  }
}
