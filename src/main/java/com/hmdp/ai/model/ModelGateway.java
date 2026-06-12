package com.hmdp.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.intent.IntentRouteCandidate;
import com.hmdp.ai.intent.IntentRouteSource;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.dto.ai.ReviewEvidence;
import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.service.ai.ShopFreeChatAIService;
import com.hmdp.service.ai.ShopAIService;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Component
public class ModelGateway {

    public static final String MODEL_NAME = "configured-chat-model";

    @Resource
    private ShopAIService shopAIService;

    @Resource
    private ShopFreeChatAIService shopFreeChatAIService;

    @Value("${hmdp.ai.model.timeout-seconds:30}")
    private long timeoutSeconds;

    @Value("${hmdp.ai.resilience.max-concurrent-calls:8}")
    private int maxConcurrentCalls;

    @Value("${hmdp.ai.resilience.rate-limit-period-seconds:1}")
    private long rateLimitPeriodSeconds;

    @Value("${hmdp.ai.resilience.rate-limit-permits:2}")
    private int rateLimitPermits;

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

    public ShopAIAnalysisResult generateStructuredSummary(String prompt, ShopAnalysisContext context) throws Exception {
        String json = execute("generateStructuredAnalysis", () -> shopAIService.generateStructuredAnalysis(prompt));
        return parseStructuredAnalysis(json, context);
    }

    public String generateAnswer(String memoryId, String prompt) {
        return executeUnchecked("analyzeShopData", () -> shopAIService.analyzeShopData(memoryId, prompt));
    }

    public String generateComparison(String memoryId, String prompt) {
        return executeUnchecked("analyzeShopData", () -> shopAIService.analyzeShopData(memoryId, prompt));
    }

    public String generateRecommendation(String memoryId, String prompt) {
        return executeUnchecked("analyzeShopData", () -> shopAIService.analyzeShopData(memoryId, prompt));
    }

    public String generateFreeChat(String memoryId, String prompt) {
        return executeUnchecked("freeChat", () -> shopFreeChatAIService.chat(memoryId, prompt));
    }

    public IntentRouteCandidate classifyIntent(String prompt) throws Exception {
        String json = execute("classifyIntent", () -> shopAIService.classifyIntent(prompt));
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

    private <T> T executeUnchecked(String operation, Callable<T> callable) {
        try {
            return execute(operation, callable);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelGatewayException(operation, e);
        }
    }

    private Flux<String> decorateStream(Flux<String> source) {
        ensureResilienceInitialized();
        return source
                .timeout(timeout())
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RateLimiterOperator.of(rateLimiter));
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
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        long value = node.asLong(0L);
        return value > 0 ? value : null;
    }

    private Integer readInteger(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        int value = node.asInt(0);
        return value > 0 ? Math.min(10, value) : null;
    }

    private String readText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim()) ? null : value.trim();
    }

    static class ModelGatewayException extends RuntimeException {
        ModelGatewayException(String operation, Throwable cause) {
            super("AI model operation failed: " + operation, cause);
        }
    }
}
