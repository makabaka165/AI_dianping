package com.hmdp.ai.runtime.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MemoryRelevanceRanker {
  public List<Map<String, Object>> rank(List<Map<String, Object>> values, String query, int limit) {
    Set<String> queryTerms = terms(query);
    List<ScoredMemory> scored = new ArrayList<>();
    int order = 0;
    for (Map<String, Object> value : values) {
      Set<String> memoryTerms = terms(String.valueOf(value));
      long matches = queryTerms.stream().filter(memoryTerms::contains).count();
      double confidence = number(value.get("confidence"));
      scored.add(new ScoredMemory(value, matches * 10 + confidence, order++));
    }
    scored.sort(
        Comparator.comparingDouble(ScoredMemory::getScore)
            .reversed()
            .thenComparingInt(ScoredMemory::getOrder));
    List<Map<String, Object>> result = new ArrayList<>();
    for (ScoredMemory value : scored) {
      if (result.size() >= Math.max(0, limit)) break;
      result.add(value.value);
    }
    return result;
  }

  private Set<String> terms(String value) {
    Set<String> result = new HashSet<>();
    if (value == null) return result;
    for (String term : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
      if (term.length() > 1) result.add(term);
    }
    return result;
  }

  private double number(Object value) {
    return value instanceof Number ? ((Number) value).doubleValue() : 0;
  }

  private static final class ScoredMemory {
    private final Map<String, Object> value;
    private final double score;
    private final int order;

    private ScoredMemory(Map<String, Object> value, double score, int order) {
      this.value = value;
      this.score = score;
      this.order = order;
    }

    private double getScore() {
      return score;
    }

    private int getOrder() {
      return order;
    }
  }
}
