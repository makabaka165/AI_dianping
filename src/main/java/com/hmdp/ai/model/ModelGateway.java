package com.hmdp.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.intent.IntentRouteCandidate;
import com.hmdp.ai.intent.IntentRouteSource;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopRecommendationItem;
import com.hmdp.entity.Shop;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.AiTokenEstimator;
import com.hmdp.service.ai.ShopAIService;
import com.hmdp.service.ai.ShopFreeChatAIService;
import com.hmdp.service.ai.ShopRepairAIService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ModelGateway {

    public static final String DEFAULT_MODEL_NAME = "configured-chat-model";

    @Resource
    private ShopAIService shopAIService;

    @Resource
    private ShopFreeChatAIService shopFreeChatAIService;

    @Resource
    private ShopRepairAIService shopRepairAIService;

    @Resource
    private AiMetricsService aiMetricsService;

    @Resource
    private AiTokenEstimator aiTokenEstimator;

    @Value("${hmdp.ai.model.timeout-seconds:30}")
    private long timeoutSeconds;

    @Value("${hmdp.ai.resilience.max-concurrent-calls:8}")
    private int maxConcurrentCalls;

    @Value("${hmdp.ai.resilience.rate-limit-period-seconds:1}")
    private long rateLimitPeriodSeconds;

    @Value("${hmdp.ai.resilience.rate-limit-permits:2}")
    private int rateLimitPermits;

    @Value("${langchain4j.open-ai.chat-model.model-name:" + DEFAULT_MODEL_NAME + "}")
    private String configuredModelName;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile CircuitBreaker circuitBreaker;
    private volatile Bulkhead bulkhead;
    private volatile RateLimiter rateLimiter;
    private volatile TimeLimiter timeLimiter;
    private volatile ExecutorService executorService;

    @PostConstruct
    void initResilience() {
        ensureResilienceInitialized();
    }

    @PreDestroy
    void shutdown() {
        ExecutorService executor = executorService;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public String modelName() {
        return configuredModelName == null || configuredModelName.trim().isEmpty()
                ? DEFAULT_MODEL_NAME
                : configuredModelName.trim();
    }

    public ShopAIAnalysisResult generateStructuredSummary(String prompt, ShopAnalysisContext context) throws Exception {
        String json = executeText("generateStructuredAnalysis", prompt,
                () -> shopAIService.generateStructuredAnalysis(prompt));
        try {
            return parseStructuredAnalysis(json);
        } catch (Exception e) {
            return repairStructuredSummary(prompt, context, "JSON 解析失败：" + e.getMessage());
        }
    }

    public ShopAIAnalysisResult repairStructuredSummary(String prompt,
                                                        ShopAnalysisContext context,
                                                        String qualityReason) throws Exception {
        String repairPrompt = repairPrompt(prompt, qualityReason,
                "请重新输出严格 JSON，字段必须包含 summary、sentiment、keywords、pros、cons、confidence、evidenceIds。");
        String json = executeText("generateStructuredAnalysis.repair", repairPrompt,
                () -> shopRepairAIService.generateStructuredAnalysis(repairPrompt));
        return parseStructuredAnalysis(json);
    }

    public ShopQAResult generateStructuredAnswer(String memoryId,
                                                 String prompt,
                                                 Long shopId,
                                                 String question,
                                                 List<EvidenceItem> evidence) {
        String json = executeTextUnchecked("ask:analyzeShopData", prompt,
                () -> shopAIService.analyzeShopData(memoryId, prompt));
        try {
            return parseQA(json, shopId, question);
        } catch (Exception e) {
            return repairStructuredAnswer(memoryId, prompt, shopId, question, "JSON 解析失败：" + e.getMessage());
        }
    }

    public ShopQAResult repairStructuredAnswer(String memoryId,
                                               String prompt,
                                               Long shopId,
                                               String question,
                                               String qualityReason) {
        String repairPrompt = repairPrompt(prompt, qualityReason,
                "请重新输出严格 JSON，字段必须包含 shopId、question、answer、evidenceIds、insufficientEvidence。");
        String json = executeTextUnchecked("ask:analyzeShopData.repair", repairPrompt,
                () -> shopRepairAIService.analyzeShopData(memoryId, repairPrompt));
        return parseQA(json, shopId, question);
    }

    public ShopCompareResult generateStructuredComparison(String memoryId,
                                                         String prompt,
                                                         Long shopId1,
                                                         Long shopId2,
                                                         String aspect,
                                                         List<EvidenceItem> evidence) {
        String json = executeTextUnchecked("compare:analyzeShopData", prompt,
                () -> shopAIService.analyzeShopData(memoryId, prompt));
        try {
            return parseCompare(json, shopId1, shopId2, aspect);
        } catch (Exception e) {
            return repairStructuredComparison(memoryId, prompt, shopId1, shopId2, aspect,
                    "JSON 解析失败：" + e.getMessage());
        }
    }

    public ShopCompareResult repairStructuredComparison(String memoryId,
                                                       String prompt,
                                                       Long shopId1,
                                                       Long shopId2,
                                                       String aspect,
                                                       String qualityReason) {
        String repairPrompt = repairPrompt(prompt, qualityReason,
                "请重新输出严格 JSON，字段必须包含 shopId1、shopId2、aspect、conclusion、winnerByAspect、shop1Score、shop2Score、shop1Pros、shop2Pros、riskNotes、evidenceIds。");
        String json = executeTextUnchecked("compare:analyzeShopData.repair", repairPrompt,
                () -> shopRepairAIService.analyzeShopData(memoryId, repairPrompt));
        return parseCompare(json, shopId1, shopId2, aspect);
    }

    public ShopRecommendResult generateStructuredRecommendation(String memoryId,
                                                                String prompt,
                                                                String userPreference,
                                                                String category,
                                                                List<Shop> candidates,
                                                                List<EvidenceItem> evidence) {
        String json = executeTextUnchecked("recommend:analyzeShopData", prompt,
                () -> shopAIService.analyzeShopData(memoryId, prompt));
        try {
            return parseRecommend(json, userPreference, category, candidates);
        } catch (Exception e) {
            return repairStructuredRecommendation(memoryId, prompt, userPreference, category, candidates,
                    "JSON 解析失败：" + e.getMessage());
        }
    }

    public ShopRecommendResult repairStructuredRecommendation(String memoryId,
                                                              String prompt,
                                                              String userPreference,
                                                              String category,
                                                              List<Shop> candidates,
                                                              String qualityReason) {
        String repairPrompt = repairPrompt(prompt, qualityReason,
                "请重新输出严格 JSON，字段必须包含 userPreference、category、message、items，items 内包含 rank、shopId、shopName、reason、suitableFor、uncertainty、evidenceIds、confidence。");
        String json = executeTextUnchecked("recommend:analyzeShopData.repair", repairPrompt,
                () -> shopRepairAIService.analyzeShopData(memoryId, repairPrompt));
        return parseRecommend(json, userPreference, category, candidates);
    }

    public String generateFreeChat(String memoryId, String prompt) {
        return executeTextUnchecked("freeChat", prompt,
                () -> shopFreeChatAIService.chat(memoryId, prompt));
    }

    public String repairFreeChat(String memoryId, String prompt, String qualityReason) {
        String repairPrompt = repairPrompt(prompt, qualityReason,
                "请重新生成一个简洁、安全、只说明能力范围或追问必要参数的回答。");
        return executeTextUnchecked("freeChat.repair", repairPrompt,
                () -> shopRepairAIService.chat(memoryId, repairPrompt));
    }

    public IntentRouteCandidate classifyIntent(String prompt) throws Exception {
        String json = executeText("classifyIntent", prompt,
                () -> shopAIService.classifyIntent(prompt));
        JsonNode root = objectMapper.readTree(extractJson(json));
        ShopAIIntent intent = parseIntent(root.path("intent").asText("UNSUPPORTED"));
        return IntentRouteCandidate.builder()
                .intent(intent)
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

    public Flux<String> streamChat(String memoryId, String message) {
        return decorateStream(shopFreeChatAIService.chatStream(memoryId, message));
    }

    public Flux<String> streamAnswer(String memoryId, String prompt) {
        return decorateStream(shopAIService.chatStream(memoryId, prompt));
    }

    public Flux<String> streamComparison(String memoryId, String prompt) {
        return decorateStream(shopAIService.chatStream(memoryId, prompt));
    }

    public Flux<String> streamRecommendation(String memoryId, String prompt) {
        return decorateStream(shopAIService.chatStream(memoryId, prompt));
    }

    private <T> T execute(String operation, Callable<T> callable) throws Exception {
        ensureResilienceInitialized();
        Callable<T> decorated = Bulkhead.decorateCallable(bulkhead,
                CircuitBreaker.decorateCallable(circuitBreaker,
                        RateLimiter.decorateCallable(rateLimiter, callable)));
        return timeLimiter.executeFutureSupplier(() -> CompletableFuture.supplyAsync(() -> {
            try {
                return decorated.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new ModelGatewayException(operation, e);
            }
        }, executorService));
    }

    private String executeText(String operation, String input, Callable<String> callable) throws Exception {
        long start = System.currentTimeMillis();
        try {
            String output = execute(operation, callable);
            recordModelMetrics(operation, input, output, System.currentTimeMillis() - start, true);
            return output;
        } catch (Exception e) {
            recordModelMetrics(operation, input, null, System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    private <T> T executeUnchecked(String operation, Callable<T> callable) {
        try {
            return execute(operation, callable);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelGatewayException(operation, e);
        }
    }

    private String executeTextUnchecked(String operation, String input, Callable<String> callable) {
        try {
            return executeText(operation, input, callable);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelGatewayException(operation, e);
        }
    }

    private void recordModelMetrics(String operation,
                                    String input,
                                    String output,
                                    long durationMillis,
                                    boolean success) {
        if (aiMetricsService == null) {
            return;
        }
        int inputTokens = aiTokenEstimator == null ? 0 : aiTokenEstimator.estimate(input);
        int outputTokens = aiTokenEstimator == null ? 0 : aiTokenEstimator.estimate(output);
        aiMetricsService.recordModelCall(analysisType(operation), operation, modelName(),
                durationMillis, success, inputTokens, outputTokens);
    }

    private String analysisType(String operation) {
        if (operation == null || operation.trim().isEmpty()) {
            return "unknown";
        }
        int colon = operation.indexOf(':');
        if (colon > 0) {
            return operation.substring(0, colon);
        }
        if (operation.contains("Intent")) {
            return "intent";
        }
        if (operation.contains("freeChat")) {
            return "chat";
        }
        if (operation.contains("StructuredAnalysis")) {
            return "summary";
        }
        return operation;
    }

    private Flux<String> decorateStream(Flux<String> source) {
        ensureResilienceInitialized();
        return source
                .timeout(timeout())
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RateLimiterOperator.of(rateLimiter));
    }

    private String repairPrompt(String originalPrompt, String qualityReason, String instruction) {
        return "上一次模型输出未通过质量校验，原因：" + safeReason(qualityReason) + "\n"
                + instruction + "\n"
                + "必须继续遵守原始数据边界：只能基于给定证据，不得编造店铺信息、价格、地址、评分。\n\n"
                + "原始任务：\n"
                + (originalPrompt == null ? "" : originalPrompt);
    }

    private String safeReason(String qualityReason) {
        if (qualityReason == null || qualityReason.trim().isEmpty()) {
            return "未给出具体原因";
        }
        String trimmed = qualityReason.trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
    }

    private void ensureResilienceInitialized() {
        if (circuitBreaker != null) {
            return;
        }
        synchronized (this) {
            if (circuitBreaker != null) {
                return;
            }
            Duration timeout = timeout();
            CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)
                    .slidingWindowSize(10)
                    .minimumNumberOfCalls(5)
                    .waitDurationInOpenState(timeout.multipliedBy(2))
                    .build();
            BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                    .maxConcurrentCalls(Math.max(1, maxConcurrentCalls))
                    .maxWaitDuration(Duration.ZERO)
                    .build();
            RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                    .limitRefreshPeriod(Duration.ofSeconds(Math.max(1, rateLimitPeriodSeconds)))
                    .limitForPeriod(Math.max(1, rateLimitPermits))
                    .timeoutDuration(Duration.ZERO)
                    .build();
            TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                    .timeoutDuration(timeout)
                    .cancelRunningFuture(true)
                    .build();
            circuitBreaker = CircuitBreaker.of("shop-ai-model", circuitBreakerConfig);
            bulkhead = Bulkhead.of("shop-ai-model", bulkheadConfig);
            rateLimiter = RateLimiter.of("shop-ai-model", rateLimiterConfig);
            timeLimiter = TimeLimiter.of("shop-ai-model", timeLimiterConfig);
            executorService = Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "shop-ai-model-gateway");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    private ShopAIAnalysisResult parseStructuredAnalysis(String json) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(json));
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

    private ShopQAResult parseQA(String json, Long shopId, String question) {
        JsonNode root = readRoot(json);
        return ShopQAResult.builder()
                .shopId(readLong(root.path("shopId"), shopId))
                .question(readText(root.path("question"), question))
                .answer(readText(root.path("answer"), ""))
                .evidenceIds(readEvidenceIds(root.path("evidenceIds"), 10))
                .insufficientEvidence(root.path("insufficientEvidence").asBoolean(false))
                .build();
    }

    private ShopCompareResult parseCompare(String json, Long shopId1, Long shopId2, String aspect) {
        JsonNode root = readRoot(json);
        return ShopCompareResult.builder()
                .shopId1(readLong(root.path("shopId1"), shopId1))
                .shopId2(readLong(root.path("shopId2"), shopId2))
                .aspect(readText(root.path("aspect"), aspect))
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

    private ShopRecommendResult parseRecommend(String json,
                                               String userPreference,
                                               String category,
                                               List<Shop> candidates) {
        JsonNode root = readRoot(json);
        Map<Long, Shop> candidateMap = candidates == null ? Collections.emptyMap() : candidates.stream()
                .filter(shop -> shop != null && shop.getId() != null)
                .collect(Collectors.toMap(Shop::getId, Function.identity(), (a, b) -> a));
        List<ShopRecommendationItem> items = new ArrayList<>();
        JsonNode itemNode = root.path("items");
        if (itemNode.isArray()) {
            for (JsonNode item : itemNode) {
                Long shopId = readLong(item.path("shopId"), null);
                Shop shop = shopId == null ? null : candidateMap.get(shopId);
                items.add(ShopRecommendationItem.builder()
                        .rank(readInteger(item.path("rank"), items.size() + 1))
                        .shopId(shopId)
                        .shopName(readText(item.path("shopName"), shop == null ? null : shop.getName()))
                        .reason(readText(item.path("reason"), ""))
                        .suitableFor(readText(item.path("suitableFor"), ""))
                        .uncertainty(readText(item.path("uncertainty"), ""))
                        .evidenceIds(readEvidenceIds(item.path("evidenceIds"), 10))
                        .confidence(clampDouble(item.path("confidence").asDouble(0.6)))
                        .build());
            }
        }
        return ShopRecommendResult.builder()
                .userPreference(readText(root.path("userPreference"), userPreference))
                .category(readText(root.path("category"), category))
                .message(readText(root.path("message"), ""))
                .items(items)
                .build();
    }

    private JsonNode readRoot(String json) {
        try {
            return objectMapper.readTree(extractJson(json));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON response", e);
        }
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

    private List<String> readEvidenceIds(JsonNode node, int limit) {
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

    private ShopAIIntent parseIntent(String value) {
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

    static class ModelGatewayException extends RuntimeException {
        ModelGatewayException(String operation, Throwable cause) {
            super("AI model operation failed: " + operation, cause);
        }
    }
}
