package com.hmdp.ai.runtime.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MemorySensitivityFilter {
  public List<Map<String, Object>> filter(List<Map<String, Object>> values) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> value : values) {
      String level =
          String.valueOf(value.getOrDefault("sensitivityLevel", "NORMAL")).toUpperCase(Locale.ROOT);
      if ("HIGH".equals(level) || "CRITICAL".equals(level)) continue;
      Map<String, Object> copy = new LinkedHashMap<>(value);
      copy.remove("sensitivityLevel");
      result.add(copy);
    }
    return result;
  }

  public Map<String, Object> filterProfile(Map<String, Object> profile) {
    return sanitizeMap(profile);
  }

  private Map<String, Object> sanitizeMap(Map<?, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach(
        (rawKey, value) -> {
          String key = String.valueOf(rawKey);
          if (!sensitiveKey(key)) result.put(key, sanitizeValue(value));
        });
    return result;
  }

  private Object sanitizeValue(Object value) {
    if (value instanceof Map) return sanitizeMap((Map<?, ?>) value);
    if (value instanceof Collection) {
      List<Object> result = new ArrayList<>();
      for (Object item : (Collection<?>) value) result.add(sanitizeValue(item));
      return result;
    }
    return value;
  }

  private boolean sensitiveKey(String key) {
    return key.toLowerCase(Locale.ROOT)
        .matches(".*(secret|password|token|credential|idcard|identity|phone|email|ssn).*");
  }
}
