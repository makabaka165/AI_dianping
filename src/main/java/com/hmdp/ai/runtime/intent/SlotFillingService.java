package com.hmdp.ai.runtime.intent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SlotFillingService {
  private final IntentConfidencePolicy confidencePolicy;

  public SlotFillingService(IntentConfidencePolicy confidencePolicy) {
    this.confidencePolicy = confidencePolicy;
  }

  public IntentClassification fill(
      IntentClassification classification,
      Map<String, Object> extracted,
      Map<String, Object> workflowVariables) {
    Map<String, Object> entities = new LinkedHashMap<>(classification.getEntities());
    entities.putAll(extracted);
    mergeContext(entities, workflowVariables);
    normalizeShopIds(entities);
    List<String> missing = missing(classification.getPrimaryIntent(), entities);
    boolean clarification =
        !missing.isEmpty()
            || confidencePolicy.requiresClarification(classification.getConfidence());
    return classification.withSlots(entities, missing, clarification);
  }

  private void mergeContext(Map<String, Object> entities, Map<String, Object> variables) {
    for (String key :
        new String[] {
          "shopId",
          "shopId1",
          "shopId2",
          "shopIds",
          "aspect",
          "preference",
          "category",
          "priceRange",
          "distance",
          "queueTolerance",
          "parkingRequirement"
        }) {
      Object value = variables.get(key);
      if (value != null && !entities.containsKey(key)) entities.put(key, value);
    }
  }

  private void normalizeShopIds(Map<String, Object> entities) {
    List<Long> ids = new ArrayList<>();
    Object raw = entities.get("shopIds");
    if (raw instanceof Collection) {
      for (Object value : (Collection<?>) raw) add(ids, value);
    }
    add(ids, entities.get("shopId"));
    add(ids, entities.get("shopId1"));
    add(ids, entities.get("shopId2"));
    entities.put("shopIds", ids);
    if (!ids.isEmpty()) entities.put("shopId", ids.get(0));
    if (ids.size() > 1) {
      entities.put("shopId1", ids.get(0));
      entities.put("shopId2", ids.get(1));
    }
  }

  private void add(List<Long> ids, Object value) {
    if (value == null) return;
    try {
      long id =
          value instanceof Number
              ? ((Number) value).longValue()
              : Long.parseLong(String.valueOf(value));
      if (id > 0 && !ids.contains(id)) ids.add(id);
    } catch (NumberFormatException ignored) {
      // Invalid continuation data must not become a trusted shop identifier.
    }
  }

  private List<String> missing(String intent, Map<String, Object> entities) {
    List<String> missing = new ArrayList<>();
    List<?> shopIds =
        entities.get("shopIds") instanceof List
            ? (List<?>) entities.get("shopIds")
            : java.util.Collections.emptyList();
    if (("SHOP_SUMMARY".equals(intent) || "SHOP_QA".equals(intent)) && shopIds.isEmpty()) {
      missing.add("shopId");
    } else if ("SHOP_COMPARE".equals(intent) && shopIds.size() < 2) {
      missing.add("shopIds");
    } else if ("SHOP_RECOMMEND".equals(intent)
        && blank(entities.get("preference"))
        && blank(entities.get("category"))) {
      missing.add("preference");
    }
    return missing;
  }

  private boolean blank(Object value) {
    return value == null || String.valueOf(value).trim().isEmpty();
  }
}
