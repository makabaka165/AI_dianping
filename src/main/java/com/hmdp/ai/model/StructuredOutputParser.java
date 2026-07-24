package com.hmdp.ai.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ai.IntentRouteCandidate;
import com.hmdp.dto.ai.IntentRouteSource;
import com.hmdp.dto.ai.ShopAIIntent;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopRecommendationItem;
import com.hmdp.dto.ai.ShopView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class StructuredOutputParser {

    private static final int MAX_MODEL_OUTPUT_CHARS = 100_000;
    private static final int MAX_JSON_NESTING = 256;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public enum JsonStatus {
        COMPLETE,
        INCOMPLETE,
        INVALID
    }

    public ShopAIAnalysisResult parseStructuredAnalysis(String json) throws Exception {
        JsonNode root = readRoot(json);
        String sentiment = root.path("sentiment").asText("neutral");
        if (!Arrays.asList("positive", "negative", "neutral").contains(sentiment)) {
            sentiment = "neutral";
        }
        String summary = root.path("summary").asText();
        if (summary == null || summary.trim().length() < 10) {
            throw new IllegalArgumentException("结构化总结内容为空或过短");
        }
        return ShopAIAnalysisResult.builder()
                .summary(summary.trim())
                .sentiment(sentiment)
                .keywords(readStringArray(root.path("keywords"), 5))
                .pros(readStringArray(root.path("pros"), 5))
                .cons(readStringArray(root.path("cons"), 5))
                .confidence(clampDouble(root.path("confidence").asDouble(0.7)))
                .evidenceIds(readEvidenceIds(root.path("evidenceIds"), 10))
                .degraded(false)
                .build();
    }

    public ShopQAResult parseQA(String json, Long shopId, String question) {
        JsonNode root = readRoot(json);
        return ShopQAResult.builder()
                .shopId(readLong(root.path("shopId"), shopId))
                .question(question)
                .answer(readText(root.path("answer"), ""))
                .evidenceIds(readEvidenceIds(root.path("evidenceIds"), 10))
                .insufficientEvidence(root.path("insufficientEvidence").asBoolean(false))
                .build();
    }

    public ShopCompareResult parseCompare(String json, Long shopId1, Long shopId2, String aspect) {
        JsonNode root = readRoot(json);
        return ShopCompareResult.builder()
                .shopId1(readLong(root.path("shopId1"), shopId1))
                .shopId2(readLong(root.path("shopId2"), shopId2))
                .aspect(aspect)
                .conclusion(readText(root.path("conclusion"), ""))
                .winnerByAspect(readText(root.path("winnerByAspect"), ShopCompareResult.INSUFFICIENT))
                .shop1Score(readInteger(root.path("shop1Score"), 0))
                .shop2Score(readInteger(root.path("shop2Score"), 0))
                .shop1Pros(readStringArray(root.path("shop1Pros"), 5))
                .shop2Pros(readStringArray(root.path("shop2Pros"), 5))
                .riskNotes(readStringArray(root.path("riskNotes"), 5))
                .evidenceIds(readEvidenceIds(root.path("evidenceIds"), 10))
                .build();
    }

    public ShopRecommendResult parseRecommend(String json,
                                              String userPreference,
                                              String category,
                                              List<ShopView> candidates) {
        JsonNode root = readRoot(json);
        Map<Long, ShopView> candidateMap = candidates == null ? Collections.emptyMap() : candidates.stream()
                .filter(shop -> shop != null && shop.getId() != null)
                .collect(Collectors.toMap(ShopView::getId, Function.identity(), (a, b) -> a));
        List<ShopRecommendationItem> items = new ArrayList<>();
        JsonNode itemNode = root.path("items");
        if (itemNode.isArray()) {
            for (JsonNode item : itemNode) {
                Long shopId = readLong(item.path("shopId"), null);
                ShopView shop = shopId == null ? null : candidateMap.get(shopId);
                items.add(ShopRecommendationItem.builder()
                        .rank(readInteger(item.path("rank"), items.size() + 1))
                        .shopId(shopId)
                        .shopName(shop == null ? null : shop.getName())
                        .address(shop == null ? null : shop.getAddress())
                        .reason(readText(item.path("reason"), ""))
                        .suitableFor(readText(item.path("suitableFor"), ""))
                        .uncertainty(readText(item.path("uncertainty"), ""))
                        .evidenceIds(readEvidenceIds(item.path("evidenceIds"), 10))
                        .confidence(clampDouble(item.path("confidence").asDouble(0.6)))
                        .build());
            }
        }
        return ShopRecommendResult.builder()
                .userPreference(userPreference)
                .category(category)
                .message(readText(root.path("message"), ""))
                .items(items)
                .build();
    }

    public IntentRouteCandidate parseIntent(String json) throws Exception {
        JsonNode root = readRoot(json);
        return IntentRouteCandidate.builder()
                .intent(parseIntentValue(root.path("intent").asText("UNSUPPORTED")))
                .shopId(readLong(root.path("shopId")))
                .shopId1(readLong(root.path("shopId1")))
                .shopId2(readLong(root.path("shopId2")))
                .aspect(readText(root.path("aspect")))
                .category(readText(root.path("category")))
                .limit(readInteger(root.path("limit")))
                .userPreference(readText(root.path("userPreference")))
                .confidence(Math.max(0.0, Math.min(1.0, root.path("confidence").asDouble(0.0))))
                .missingParams(readStringList(root.path("missingParams"), 6))
                .source(IntentRouteSource.LLM)
                .build();
    }

    public String extractJson(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "";
        }
        if (content.length() > MAX_MODEL_OUTPUT_CHARS) {
            throw new IllegalArgumentException("MODEL_OUTPUT_TOO_LARGE");
        }
        Candidate incomplete = null;
        for (int start = 0; start < content.length(); start++) {
            char opening = content.charAt(start);
            if (opening != '{' && opening != '[') {
                continue;
            }
            ScanResult scan = scan(content, start);
            if (!scan.complete) {
                if (incomplete == null) {
                    incomplete = new Candidate(start, content.length());
                }
                continue;
            }
            String candidate = content.substring(start, scan.end + 1);
            try {
                JsonNode node = readStrict(candidate);
                if (node != null && (node.isObject() || node.isArray())) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // Keep looking: prose may contain a balanced brace before the actual JSON.
            }
        }
        if (incomplete != null) {
            return content.substring(incomplete.start, incomplete.end);
        }
        return content.trim();
    }

    /** Classifies a model response without silently treating a truncated JSON object as valid. */
    public JsonStatus classifyJson(String content) {
        if (content == null || content.trim().isEmpty() || content.length() > MAX_MODEL_OUTPUT_CHARS) {
            return JsonStatus.INVALID;
        }
        try {
            String extracted = extractJson(content);
            JsonNode node = readStrict(extracted);
            return node != null && (node.isObject() || node.isArray())
                    ? JsonStatus.COMPLETE : JsonStatus.INVALID;
        } catch (IllegalArgumentException e) {
            return e.getMessage() != null && e.getMessage().contains("INCOMPLETE")
                    ? JsonStatus.INCOMPLETE : classifyByBalance(content);
        } catch (Exception e) {
            return classifyByBalance(content);
        }
    }

    public List<String> readEvidenceIds(JsonNode node, int limit) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String value = evidenceId(item);
                if (value != null && !value.trim().isEmpty() && values.size() < limit) {
                    values.add(value.trim());
                }
            });
        }
        return values;
    }

    private JsonNode readRoot(String json) {
        try {
            JsonNode root = readStrict(extractJson(json));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("MODEL_OUTPUT_JSON_OBJECT_REQUIRED");
            }
            return root;
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException
                    && e.getMessage() != null
                    && e.getMessage().startsWith("MODEL_OUTPUT_")) {
                throw (IllegalArgumentException) e;
            }
            JsonStatus status = classifyByBalance(json);
            throw new IllegalArgumentException(
                    status == JsonStatus.INCOMPLETE
                            ? "MODEL_OUTPUT_INCOMPLETE_JSON"
                            : "MODEL_OUTPUT_INVALID_JSON",
                    e);
        }
    }

    private JsonNode readStrict(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("MODEL_OUTPUT_INVALID_JSON");
        }
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            JsonNode root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("MODEL_OUTPUT_TRAILING_JSON");
            }
            return root;
        }
    }

    private JsonStatus classifyByBalance(String content) {
        if (content == null || content.trim().isEmpty()) return JsonStatus.INVALID;
        for (int start = 0; start < content.length(); start++) {
            char opening = content.charAt(start);
            if (opening != '{' && opening != '[') continue;
            ScanResult scan = scan(content, start);
            if (!scan.complete) return JsonStatus.INCOMPLETE;
            try {
                JsonNode node = readStrict(content.substring(start, scan.end + 1));
                if (node != null && (node.isObject() || node.isArray())) return JsonStatus.COMPLETE;
            } catch (Exception ignored) {
                return JsonStatus.INVALID;
            }
        }
        return JsonStatus.INVALID;
    }

    private ScanResult scan(String content, int start) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '{' || ch == '[') {
                stack.push(ch);
                if (stack.size() > MAX_JSON_NESTING) return new ScanResult(false, -1);
            } else if (ch == '}' || ch == ']') {
                if (stack.isEmpty() || !matching(stack.peek(), ch)) {
                    return new ScanResult(true, i);
                }
                stack.pop();
                if (stack.isEmpty()) return new ScanResult(true, i);
            }
        }
        return new ScanResult(false, -1);
    }

    private boolean matching(char opening, char closing) {
        return (opening == '{' && closing == '}') || (opening == '[' && closing == ']');
    }

    private static final class Candidate {
        private final int start;
        private final int end;

        private Candidate(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class ScanResult {
        private final boolean complete;
        private final int end;

        private ScanResult(boolean complete, int end) {
            this.complete = complete;
            this.end = end;
        }
    }

    private String evidenceId(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return EvidenceItem.reviewId(node.asLong());
        }
        return node.asText();
    }

    private List<String> readStringArray(JsonNode node, int limit) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String value = item.asText();
                if (value != null && !value.trim().isEmpty() && values.size() < limit) {
                    values.add(value.trim());
                }
            });
        }
        return values;
    }

    private List<String> readStringList(JsonNode node, int limit) {
        if (node != null && node.isTextual()) {
            String value = node.asText();
            if (value != null && !value.trim().isEmpty()) {
                return List.of(value.trim());
            }
        }
        return readStringArray(node, limit);
    }

    private ShopAIIntent parseIntentValue(String value) {
        try {
            return ShopAIIntent.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (Exception e) {
            return ShopAIIntent.UNSUPPORTED;
        }
    }

    private Long readLong(JsonNode node) {
        return readLong(node, null);
    }

    private Long readLong(JsonNode node, Long defaultValue) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaultValue;
        }
        long value = node.asLong(0L);
        return value > 0 ? value : defaultValue;
    }

    private Integer readInteger(JsonNode node) {
        return readInteger(node, null);
    }

    private Integer readInteger(JsonNode node, Integer defaultValue) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaultValue;
        }
        int value = node.asInt(defaultValue == null ? 0 : defaultValue);
        return value > 0 ? Math.min(100, value) : defaultValue;
    }

    private String readText(JsonNode node) {
        return readText(node, null);
    }

    private String readText(JsonNode node, String defaultValue) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaultValue;
        }
        String value = node.asText();
        return value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim())
                ? defaultValue
                : value.trim();
    }

    private double clampDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
