package com.hmdp.ai.runtime.intent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class EntityExtractionService {
  private static final Pattern NUMBER = Pattern.compile("(?<!\\d)(\\d{1,12})(?!\\d)");
  private static final Pattern PRICE_RANGE =
      Pattern.compile(
          "(?:\\u4eba\\u5747|price|budget)?\\s*(\\d{1,6})\\s*(?:-|~|\\u5230|\\u81f3)\\s*(\\d{1,6})");
  private static final Pattern DISTANCE =
      Pattern.compile(
          "(\\d+(?:\\.\\d+)?)\\s*(km|kilometers?|\\u516c\\u91cc|m|meters?|\\u7c73)",
          Pattern.CASE_INSENSITIVE);

  public Map<String, Object> extract(String input, Map<String, Object> variables) {
    String text = input == null ? "" : input.trim();
    String lower = text.toLowerCase(Locale.ROOT);
    Map<String, Object> entities = new LinkedHashMap<>();
    mergeExisting(entities, variables.get("entities"));
    List<Long> shopIds = shopIds(text, variables);
    entities.put("shopIds", shopIds);
    if (!shopIds.isEmpty()) entities.put("shopId", shopIds.get(0));
    if (shopIds.size() > 1) {
      entities.put("shopId1", shopIds.get(0));
      entities.put("shopId2", shopIds.get(1));
    }
    putMatch(
        entities,
        "aspect",
        lower,
        new String[][] {
          {"service", "service|\\u670d\\u52a1"},
          {"price", "price|cost|\\u4ef7\\u683c|\\u4eba\\u5747"},
          {"environment", "environment|ambience|\\u73af\\u5883|\\u6c1b\\u56f4"},
          {"taste", "taste|food|\\u5473\\u9053|\\u53e3\\u5473|\\u83dc\\u54c1"}
        });
    putMatch(
        entities,
        "category",
        lower,
        new String[][] {
          {"restaurant", "restaurant|food|\\u9910\\u5385|\\u7f8e\\u98df"},
          {"cafe", "cafe|coffee|\\u5496\\u5561"},
          {"hotel", "hotel|\\u9152\\u5e97"}
        });
    Matcher price = PRICE_RANGE.matcher(lower);
    if (price.find()) {
      Map<String, Integer> range = new LinkedHashMap<>();
      range.put("min", Integer.parseInt(price.group(1)));
      range.put("max", Integer.parseInt(price.group(2)));
      entities.put("priceRange", range);
    }
    Matcher distance = DISTANCE.matcher(lower);
    if (distance.find()) entities.put("distance", distance.group());
    if (Pattern.compile("queue|wait|\\u6392\\u961f|\\u7b49\\u4f4d").matcher(lower).find()) {
      entities.put("queueTolerance", lower);
    }
    if (Pattern.compile("parking|\\u505c\\u8f66|\\u8f66\\u4f4d").matcher(lower).find()) {
      entities.put("parkingRequirement", true);
    }
    if (Pattern.compile("recommend|suggest|\\u63a8\\u8350|\\u9002\\u5408|\\u60f3\\u627e")
        .matcher(lower)
        .find()) {
      entities.put("preference", text);
    }
    return entities;
  }

  private List<Long> shopIds(String text, Map<String, Object> variables) {
    List<Long> ids = new ArrayList<>();
    addIds(ids, variables.get("shopIds"));
    addId(ids, variables.get("shopId"));
    Matcher matcher = NUMBER.matcher(text);
    while (matcher.find() && ids.size() < 20) addId(ids, matcher.group(1));
    return ids;
  }

  private void mergeExisting(Map<String, Object> target, Object raw) {
    if (raw instanceof Map)
      ((Map<?, ?>) raw).forEach((key, value) -> target.put(String.valueOf(key), value));
  }

  private void addIds(List<Long> target, Object raw) {
    if (raw instanceof Collection) ((Collection<?>) raw).forEach(value -> addId(target, value));
  }

  private void addId(List<Long> target, Object raw) {
    if (raw == null) return;
    try {
      long value =
          raw instanceof Number ? ((Number) raw).longValue() : Long.parseLong(String.valueOf(raw));
      if (value > 0 && !target.contains(value)) target.add(value);
    } catch (NumberFormatException ignored) {
      // Non-numeric context values are not shop identifiers.
    }
  }

  private void putMatch(
      Map<String, Object> entities, String key, String text, String[][] candidates) {
    for (String[] candidate : candidates) {
      if (Pattern.compile(candidate[1]).matcher(text).find()) {
        entities.put(key, candidate[0]);
        return;
      }
    }
  }
}
