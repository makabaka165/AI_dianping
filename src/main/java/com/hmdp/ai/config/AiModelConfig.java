package com.hmdp.ai.config;

import com.hmdp.ai.memory.ChatMemoryKeyManager;
import com.hmdp.ai.memory.RedissonChatMemoryStore;
import com.hmdp.ai.infra.DocumentQualityAssessor;
import com.hmdp.ai.port.PlatformPolicyDocumentPort;
import com.hmdp.ai.retrieval.QualityBasedContentRetriever;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Collections;

@Configuration
@Slf4j
public class AiModelConfig {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.streaming-chat-model.api-key:${langchain4j.open-ai.chat-model.api-key}}")
    private String streamingApiKey;

    @Value("${langchain4j.open-ai.embedding-model.api-key:${langchain4j.open-ai.chat-model.api-key}}")
    private String embeddingApiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name:qwen-plus}")
    private String modelName;

    @Value("${langchain4j.open-ai.streaming-chat-model.model-name:${langchain4j.open-ai.chat-model.model-name:qwen-plus}}")
    private String streamingModelName;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.streaming-chat-model.base-url:${langchain4j.open-ai.chat-model.base-url}}")
    private String streamingBaseUrl;

    @Value("${langchain4j.open-ai.embedding-model.base-url:${langchain4j.open-ai.chat-model.base-url}}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.open-ai.chat-model.temperature:0.3}")
    private double temperature;

    @Value("${langchain4j.open-ai.streaming-chat-model.temperature:${langchain4j.open-ai.chat-model.temperature:0.3}}")
    private double streamingTemperature;

    @Value("${langchain4j.open-ai.chat-model.repair-temperature:0.1}")
    private double repairTemperature;

    @Value("${langchain4j.open-ai.chat-model.max-tokens:1500}")
    private Integer chatMaxTokens;

    @Value("${langchain4j.open-ai.streaming-chat-model.max-tokens:${langchain4j.open-ai.chat-model.max-tokens:1500}}")
    private Integer streamingMaxTokens;

    @Value("${langchain4j.open-ai.repair-chat-model.max-tokens:${langchain4j.open-ai.chat-model.max-tokens:1500}}")
    private Integer repairMaxTokens;

    @Value("${langchain4j.open-ai.chat-model.log-requests:false}")
    private boolean logRequests;

    @Value("${langchain4j.open-ai.chat-model.log-responses:false}")
    private boolean logResponses;

    @Value("${langchain4j.open-ai.embedding-model.model-name:text-embedding-v3}")
    private String embeddingModelName;

    @Value("${hmdp.ai.model.timeout-seconds:30}")
    private long modelTimeoutSeconds;

    @Value("${chat.memory.max-messages:20}")
    private int maxMessages;

    @Value("${rag.redis.host:localhost}")
    private String redisHost;

    @Value("${rag.redis.port:6380}")
    private int redisPort;

    @Value("${rag.platform-policy.index-name:platform_policy_kb}")
    private String platformPolicyIndexName;

    @Value("${rag.platform-policy.dimension:${rag.redis.dimension:1536}}")
    private int platformPolicyDimension;

    @Value("${rag.review.index-name:shop_review_kb}")
    private String reviewIndexName;

    @Value("${rag.review.dimension:${rag.redis.dimension:1536}}")
    private int reviewDimension;




    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedissonChatMemoryStore chatMemoryStore) {
        log.info("Initialized ChatMemoryProvider, maxMessages={}", maxMessages);

        return memoryId -> {
            log.debug("Created ChatMemory for memoryId={}", memoryId);
            return MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(maxMessages)
                    .chatMemoryStore(chatMemoryStore)
                    .build();
        };
    }

    // ========== AI model configuration ==========

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        log.info("Initialized ChatLanguageModel, model={}", modelName);
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .temperature(temperature)
                .maxTokens(safeMaxTokens(chatMaxTokens))
                .timeout(modelTimeout())
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    @Bean("repairChatLanguageModel")
    public ChatLanguageModel repairChatLanguageModel() {
        log.info("Initialized repair ChatLanguageModel, model={}, temperature={}", modelName, repairTemperature);
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .temperature(repairTemperature)
                .maxTokens(safeMaxTokens(repairMaxTokens))
                .timeout(modelTimeout())
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatModel() {
        log.info("Initialized StreamingChatModel, model={}", streamingModelName);
        return OpenAiStreamingChatModel.builder()
                .apiKey(streamingApiKey)
                .modelName(streamingModelName)
                .baseUrl(streamingBaseUrl)
                .temperature(streamingTemperature)
                .maxTokens(safeMaxTokens(streamingMaxTokens))
                .timeout(modelTimeout())
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("Initialized EmbeddingModel, model={}", embeddingModelName);
        return OpenAiEmbeddingModel.builder()
                .apiKey(embeddingApiKey)
                .modelName(embeddingModelName)
                .baseUrl(embeddingBaseUrl)
                .timeout(modelTimeout())
                .build();
    }

    private Duration modelTimeout() {
        return Duration.ofSeconds(Math.max(1, modelTimeoutSeconds));
    }

    private int safeMaxTokens(Integer value) {
        return value == null || value <= 0 ? 1500 : value;
    }


    /**
     * Creates a Redis embedding store. Platform policy and shop review use separate indexes.
     */
    private RedisEmbeddingStore buildRedisEmbeddingStore(String indexName, int dimension) {
        log.info("Creating RedisEmbeddingStore, host={}, port={}, index={}, dimension={}",
                redisHost, redisPort, indexName, dimension);

        RedisEmbeddingStore store = RedisEmbeddingStore.builder()
                .host(redisHost)
                .port(redisPort)
                .indexName(indexName)
                .dimension(dimension)
                .build();
        log.info("RedisEmbeddingStore ready, index={}", indexName);
        return store;
    }

    @Bean("platformPolicyInMemoryEmbeddingStore")
    @org.springframework.context.annotation.Profile({"local", "dev", "test"})
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true", matchIfMissing = false)
    public InMemoryEmbeddingStore<TextSegment> platformPolicyInMemoryEmbeddingStore() {
        log.info("Initialized platform policy in-memory embedding store");
        return new InMemoryEmbeddingStore<>();
    }

    @Bean("shopReviewInMemoryEmbeddingStore")
    @org.springframework.context.annotation.Profile({"local", "dev", "test"})
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true", matchIfMissing = false)
    public InMemoryEmbeddingStore<TextSegment> shopReviewInMemoryEmbeddingStore() {
        log.info("Initialized shop review in-memory embedding store");
        return new InMemoryEmbeddingStore<>();
    }

    @Bean("platformPolicyEmbeddingStore")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true")
    public EmbeddingStore<TextSegment> platformPolicyEmbeddingStore() {

        log.info("Initializing platform policy embedding store");
        try {
            RedisEmbeddingStore redisEmbeddingStore = buildRedisEmbeddingStore(platformPolicyIndexName, platformPolicyDimension);
            log.info("Platform policy bootstrap is managed by KnowledgeBootstrapRunner");
            return redisEmbeddingStore;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "RAG is enabled but platform policy Redis Stack embedding store is unavailable", e);
        }
    }

    @Bean("shopReviewEmbeddingStore")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true")
    public EmbeddingStore<TextSegment> shopReviewEmbeddingStore() {

        log.info("Initializing shop review embedding store");
        try {
            return buildRedisEmbeddingStore(reviewIndexName, reviewDimension);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "RAG is enabled but shop review Redis Stack embedding store is unavailable", e);
        }
    }

    @Bean("platformPolicyContentRetriever")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true")
    public ContentRetriever platformPolicyContentRetriever(
            @Qualifier("platformPolicyEmbeddingStore")
            EmbeddingStore<TextSegment> platformPolicyEmbeddingStore,
            EmbeddingModel embeddingModel,
            DocumentQualityAssessor documentQualityAssessor,
            PlatformPolicyDocumentPort platformPolicyDocumentPort) {

        log.info("Initialized platform policy ContentRetriever, minScore=0.5, maxResults=5");
        ContentRetriever delegate = QualityBasedContentRetriever.builder()
                .embeddingStore(platformPolicyEmbeddingStore)
                .embeddingModel(embeddingModel)
                .platformPolicyDocumentPort(platformPolicyDocumentPort)
                .minScore(0.5)
                .maxResults(5)
                .build();
        return delegate;
    }

    @Bean("platformPolicyContentRetriever")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "false", matchIfMissing = true)
    public ContentRetriever noopPlatformPolicyContentRetriever() {
        log.info("rag.enabled=false, using empty platform policy ContentRetriever");
        return query -> Collections.emptyList();
    }

}
