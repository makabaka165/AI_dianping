package com.hmdp.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ai.ReviewEvidence;
import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.service.ai.ShopAIService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ModelGateway {

    public static final String MODEL_NAME = "configured-chat-model";

    @Resource
    private ShopAIService shopAIService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ShopAIAnalysisResult generateStructuredSummary(String prompt, ShopAnalysisContext context) throws Exception {
        String json = shopAIService.generateStructuredAnalysis(prompt);
        return parseStructuredAnalysis(json, context);
    }

    public String generateAnswer(String memoryId, String prompt) {
        return shopAIService.analyzeShopData(memoryId, prompt);
    }

    public String generateComparison(String memoryId, String prompt) {
        return shopAIService.analyzeShopData(memoryId, prompt);
    }

    public String generateRecommendation(String memoryId, String prompt) {
        return shopAIService.analyzeShopData(memoryId, prompt);
    }

    public String generateFreeChat(String memoryId, String prompt) {
        return shopAIService.analyzeShopData(memoryId, prompt);
    }

    public Flux<String> streamChat(String memoryId, String message) {
        return shopAIService.chatStream(memoryId, message);
    }

    private ShopAIAnalysisResult parseStructuredAnalysis(String json, ShopAnalysisContext context) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(json));
        String sentiment = root.path("sentiment").asText("neutral");
        if (!Arrays.asList("positive", "negative", "neutral").contains(sentiment)) {
            sentiment = "neutral";
        }
        List<Long> allowedIds = context.safeEvidence().stream()
                .map(ReviewEvidence::getBlogId)
                .collect(Collectors.toList());
        List<Long> evidenceIds = new ArrayList<>();
        root.path("evidenceIds").forEach(node -> {
            long id = node.asLong();
            if (allowedIds.contains(id)) {
                evidenceIds.add(id);
            }
        });
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
                .confidence(root.path("confidence").asDouble(context.safeEvidence().isEmpty() ? 0.3 : 0.7))
                .evidenceIds(evidenceIds)
                .degraded(false)
                .build();
    }

    private String extractJson(String content) {
        if (content == null) {
            return "{}";
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
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
}
