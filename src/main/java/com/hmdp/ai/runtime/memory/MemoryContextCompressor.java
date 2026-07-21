package com.hmdp.ai.runtime.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MemoryContextCompressor {
  private final ObjectMapper mapper;

  public MemoryContextCompressor(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public List<Map<String, Object>> compress(List<Map<String, Object>> values, int tokenBudget) {
    int characterBudget = Math.max(0, tokenBudget) * 4;
    int used = 0;
    Set<String> seen = new LinkedHashSet<>();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> value : values) {
      String encoded = json(value);
      if (!seen.add(encoded)) continue;
      if (used + encoded.length() > characterBudget) continue;
      result.add(value);
      used += encoded.length();
    }
    return result;
  }

  public String compressText(String value, int tokenBudget) {
    if (value == null) return "";
    int limit = Math.max(0, tokenBudget) * 4;
    return value.length() <= limit ? value : value.substring(0, limit);
  }

  private String json(Map<String, Object> value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalStateException("MEMORY_CONTEXT_INVALID", error);
    }
  }
}
