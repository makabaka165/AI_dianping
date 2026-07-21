package com.hmdp.ai.runtime.intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class IntentRuleClassifier {
  private static final Map<String, Pattern> PATTERNS = patterns();

  public IntentClassification classify(String input, Map<String, Object> entities) {
    String text = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    List<Score> scores = new ArrayList<>();
    score(scores, "SHOP_COMPARE", text, 0.94);
    score(scores, "SHOP_RECOMMEND", text, 0.91);
    score(scores, "SHOP_SUMMARY", text, 0.89);
    score(scores, "KNOWLEDGE_QUERY", text, 0.87);
    score(scores, "SHOP_QA", text, 0.82);
    scores.sort((left, right) -> Double.compare(right.confidence, left.confidence));
    if (scores.isEmpty()) {
      return new IntentClassification(
          "UNKNOWN", Collections.emptyList(), 0.35, entities, Collections.emptyList(), true);
    }
    Score primary = scores.get(0);
    List<String> secondary = new ArrayList<>();
    for (int index = 1; index < scores.size(); index++) {
      if (primary.confidence - scores.get(index).confidence <= 0.08) {
        secondary.add(scores.get(index).intent);
      }
    }
    return new IntentClassification(
        primary.intent, secondary, primary.confidence, entities, Collections.emptyList(), false);
  }

  private void score(List<Score> scores, String intent, String text, double base) {
    if (PATTERNS.get(intent).matcher(text).find()) scores.add(new Score(intent, base));
  }

  private static Map<String, Pattern> patterns() {
    Map<String, Pattern> values = new LinkedHashMap<>();
    values.put(
        "SHOP_COMPARE",
        Pattern.compile(
            "(?:compare|comparison|versus|\\bvs\\b|\\bpk\\b|\\u5bf9\\u6bd4|\\u6bd4\\u8f83|\\u533a\\u522b|\\u54ea\\u5bb6\\u66f4)"));
    values.put(
        "SHOP_RECOMMEND",
        Pattern.compile(
            "(?:recommend|suggest|best for|\\u63a8\\u8350|\\u9002\\u5408|\\u54ea\\u5bb6\\u597d|\\u53bb\\u54ea)"));
    values.put(
        "SHOP_SUMMARY",
        Pattern.compile(
            "(?:summary|summarize|overview|\\u603b\\u7ed3|\\u6982\\u62ec|\\u7efc\\u8ff0|\\u8bc4\\u4ef7\\u5982\\u4f55)"));
    values.put(
        "KNOWLEDGE_QUERY",
        Pattern.compile(
            "(?:knowledge|policy|document|manual|\\u77e5\\u8bc6|\\u5236\\u5ea6|\\u653f\\u7b56|\\u6587\\u6863|\\u624b\\u518c)"));
    values.put(
        "SHOP_QA",
        Pattern.compile(
            "(?:shop|store|restaurant|service|price|environment|review|\\u5e97\\u94fa|\\u95e8\\u5e97|\\u9910\\u5385|\\u670d\\u52a1|\\u4ef7\\u683c|\\u73af\\u5883|\\u8bc4\\u4ef7|\\u5473\\u9053)"));
    return Collections.unmodifiableMap(values);
  }

  private static final class Score {
    private final String intent;
    private final double confidence;

    private Score(String intent, double confidence) {
      this.intent = intent;
      this.confidence = confidence;
    }
  }
}
