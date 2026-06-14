package com.hmdp.ai.config;

import com.hmdp.ai.memory.ChatMemoryKeyManager;
import com.hmdp.ai.memory.RedissonChatMemoryStore;
import com.hmdp.ai.infra.DocumentQualityAssessor;
import com.hmdp.ai.port.PlatformPolicyDocumentPort;
import com.hmdp.ai.retrieval.QualityBasedContentRetriever;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
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
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
@Slf4j
public class AiModelConfig {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name:qwen-plus}")
    private String modelName;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.temperature:0.3}")
    private double temperature;

    @Value("${langchain4j.open-ai.chat-model.repair-temperature:0.1}")
    private double repairTemperature;

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

    @Value("${rag.data.auto-import:false}")
    private boolean autoImportData;

    @Value("${rag.redis.fallback-to-memory:false}")
    private boolean ragRedisFallbackToMemory;

    private static final long IMPORT_RETRY_COOLDOWN_MS = 5 * 60_000L;

    private final AtomicBoolean platformPolicyImported = new AtomicBoolean(false);
    private volatile long platformPolicyNextRetryAt = 0L;

    @Bean
    public RedissonChatMemoryStore chatMemoryStore(
            RedissonClient redissonClient,
            ChatMemoryKeyManager keyManager) {
        log.info("初始化 RedissonChatMemoryStore");
        return new RedissonChatMemoryStore(redissonClient, keyManager);
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedissonChatMemoryStore chatMemoryStore) {
        log.info("初始化 ChatMemoryProvider，最大消息数: {}", maxMessages);

        return memoryId -> {
            log.debug("为记忆ID {} 创建ChatMemory", memoryId);
            return MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(maxMessages)
                    .chatMemoryStore(chatMemoryStore)
                    .build();
        };
    }

    // ========== AI模型配置 ==========

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        log.info("初始化 ChatLanguageModel: {}", modelName);
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .temperature(temperature)
                .maxTokens(1500)
                .timeout(modelTimeout())
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    @Bean("repairChatLanguageModel")
    public ChatLanguageModel repairChatLanguageModel() {
        log.info("初始化 Repair ChatLanguageModel: {}, temperature={}", modelName, repairTemperature);
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .temperature(repairTemperature)
                .maxTokens(1500)
                .timeout(modelTimeout())
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatModel() {
        log.info("初始化 StreamingChatModel: {}", modelName);
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .temperature(temperature)
                .maxTokens(1500)
                .timeout(modelTimeout())
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("初始化 EmbeddingModel: {}", embeddingModelName);
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(embeddingModelName)
                .baseUrl(baseUrl)
                .timeout(modelTimeout())
                .build();
    }

    private Duration modelTimeout() {
        return Duration.ofSeconds(Math.max(1, modelTimeoutSeconds));
    }


    /**
     * 创建 Redis 向量存储。平台政策 FAQ 与店铺评论使用独立索引，避免不同 RAG 场景相互污染。
     */
    private RedisEmbeddingStore buildRedisEmbeddingStore(String indexName, int dimension) {
        log.info("创建 RedisEmbeddingStore - RAG存储 - 主机: {}, 端口: {}, 索引: {}, 维度: {}",
                redisHost, redisPort, indexName, dimension);

        RedisEmbeddingStore store = RedisEmbeddingStore.builder()
                .host(redisHost)
                .port(redisPort)
                .indexName(indexName)
                .dimension(dimension)
                .build();
        log.info("RedisEmbeddingStore 创建完成, index={}", indexName);
        return store;
    }

    @Bean("platformPolicyInMemoryEmbeddingStore")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true", matchIfMissing = false)
    public InMemoryEmbeddingStore<TextSegment> platformPolicyInMemoryEmbeddingStore() {
        log.info("初始化平台政策 InMemoryEmbeddingStore - 内存回退");
        return new InMemoryEmbeddingStore<>();
    }

    @Bean("shopReviewInMemoryEmbeddingStore")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true", matchIfMissing = false)
    public InMemoryEmbeddingStore<TextSegment> shopReviewInMemoryEmbeddingStore() {
        log.info("初始化店铺评论 InMemoryEmbeddingStore - 内存回退");
        return new InMemoryEmbeddingStore<>();
    }

    @Bean("platformPolicyEmbeddingStore")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true")
    public EmbeddingStore<TextSegment> platformPolicyEmbeddingStore(
            @Qualifier("platformPolicyInMemoryEmbeddingStore")
            InMemoryEmbeddingStore<TextSegment> platformPolicyInMemoryEmbeddingStore) {

        log.info("初始化平台政策向量存储...");
        try {
            RedisEmbeddingStore redisEmbeddingStore = buildRedisEmbeddingStore(platformPolicyIndexName, platformPolicyDimension);
            if (autoImportData) {
                log.info("rag.data.auto-import=true, platform policy import will run lazily on first retrieval");
            } else {
                log.info("rag.data.auto-import=false, skip platform policy vector store import");
            }
            return redisEmbeddingStore;
        } catch (Exception e) {
            if (!ragRedisFallbackToMemory) {
                throw new IllegalStateException(
                        "RAG is enabled but platform policy Redis Stack embedding store is unavailable. "
                                + "Set rag.redis.fallback-to-memory=true only for dev/test fallback.",
                        e);
            }
            log.warn("Platform policy Redis embedding store unavailable, using in-memory fallback: {}", e.getMessage());
            return platformPolicyInMemoryEmbeddingStore;
        }
    }

    @Bean("shopReviewEmbeddingStore")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true")
    public EmbeddingStore<TextSegment> shopReviewEmbeddingStore(
            @Qualifier("shopReviewInMemoryEmbeddingStore")
            InMemoryEmbeddingStore<TextSegment> shopReviewInMemoryEmbeddingStore) {

        log.info("初始化店铺评论向量存储...");
        try {
            return buildRedisEmbeddingStore(reviewIndexName, reviewDimension);
        } catch (Exception e) {
            if (!ragRedisFallbackToMemory) {
                throw new IllegalStateException(
                        "RAG is enabled but shop review Redis Stack embedding store is unavailable. "
                                + "Set rag.redis.fallback-to-memory=true only for dev/test fallback.",
                        e);
            }
            log.warn("Shop review Redis embedding store unavailable, using in-memory fallback: {}", e.getMessage());
            return shopReviewInMemoryEmbeddingStore;
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

        log.info("初始化平台政策 ContentRetriever，最小分数: 0.5, 最大结果数: 5");
        ContentRetriever delegate = QualityBasedContentRetriever.builder()
                .embeddingStore(platformPolicyEmbeddingStore)
                .embeddingModel(embeddingModel)
                .minScore(0.5)
                .maxResults(5)
                .build();
        return query -> {
            initializePlatformPolicyStoreIfNeeded(platformPolicyEmbeddingStore, embeddingModel, documentQualityAssessor, platformPolicyDocumentPort);
            return delegate.retrieve(query);
        };
    }

    @Bean("platformPolicyContentRetriever")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "false", matchIfMissing = true)
    public ContentRetriever noopPlatformPolicyContentRetriever() {
        log.info("rag.enabled=false, 使用空平台政策 ContentRetriever");
        return query -> Collections.emptyList();
    }


    private boolean initializeVectorStore(EmbeddingStore<TextSegment> embeddingStore,
                                          EmbeddingModel embeddingModel,
                                          DocumentQualityAssessor documentQualityAssessor,
                                          PlatformPolicyDocumentPort platformPolicyDocumentPort) {
        try {
            // 1. 加载文档
            List<Document> documents = loadDocuments();

            if (documents.isEmpty()) {
                log.warn("未找到任何文档用于初始化向量数据库");
                return false;
            }

            // 记录加载的文档内容用于调试
            for (int i = 0; i < documents.size(); i++) {
                String contentPreview = documents.get(i).text().length() > 100 ? 
                    documents.get(i).text().substring(0, 100) + "..." : 
                    documents.get(i).text();
                log.debug("文档 {} 内容预览: {}", i+1, contentPreview);
            }

            // 2. 文档分割器 - 调整分割参数以更好地适应中文文档
            DocumentSplitter splitter = DocumentSplitters.recursive(300, 30);
            
            // 3. 构建导入器并导入文档
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .embeddingStore(embeddingStore)
                    .documentSplitter(splitter)
                    .embeddingModel(embeddingModel)
                    .build();

            log.info("开始向向量数据库导入 {} 个文档...", documents.size());
            ingestor.ingest(documents);

            // 4. 将文档添加到文档管理系统
            for (Document document : documents) {
                LocalDateTime now = LocalDateTime.now();
                double qualityScore = documentQualityAssessor.assessQuality(document);
                platformPolicyDocumentPort.saveImportedDocument(
                        "imported-document-" + UUID.randomUUID().toString().substring(0, 8),
                        "system-initial-import",
                        "txt",
                        now,
                        now,
                        qualityScore,
                        document.text().length(),
                        document);
            }

            log.info("✅ 成功将 {} 个文档导入向量数据库！", documents.size());
            return true;

        } catch (Exception e) {
            log.warn("Platform policy vector import skipped because embedding service is unavailable or import failed: {}",
                    e.getMessage(), e);
            return false;
        }
    }

    private void initializePlatformPolicyStoreIfNeeded(EmbeddingStore<TextSegment> embeddingStore,
                                                       EmbeddingModel embeddingModel,
                                                       DocumentQualityAssessor documentQualityAssessor,
                                                       PlatformPolicyDocumentPort platformPolicyDocumentPort) {
        if (!autoImportData || platformPolicyImported.get()) {
            return;
        }
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (platformPolicyImported.get() || now < platformPolicyNextRetryAt) {
                return;
            }
            if (initializeVectorStore(embeddingStore, embeddingModel, documentQualityAssessor, platformPolicyDocumentPort)) {
                platformPolicyImported.set(true);
            } else {
                platformPolicyNextRetryAt = now + IMPORT_RETRY_COOLDOWN_MS;
            }
        }
    }




    // ========== 文档加载方法 ==========
    private List<Document> loadDocuments() {
        try {
            // 加载所有支持的文档格式
            List<Document> allDocuments = new ArrayList<>();

            // 使用Spring的ResourcePatternResolver来加载resources下的文件
            ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

            // 1. 加载TXT和MD文件
            try {
                List<Document> textDocs = loadTextDocuments(resourceResolver);
                allDocuments.addAll(textDocs);
                log.info("加载文本文档: {} 个", textDocs.size());
            } catch (Exception e) {
                log.warn("加载文本文档失败: {}", e.getMessage());
            }

            // 2. 加载PDF文件
            try {
                List<Document> pdfDocs = loadPdfDocuments(resourceResolver);
                allDocuments.addAll(pdfDocs);
                log.info("加载PDF文档: {} 个", pdfDocs.size());
            } catch (Exception e) {
                log.warn("加载PDF文档失败: {}", e.getMessage());
            }

            return allDocuments;

        } catch (Exception e) {
            log.error("加载文档失败", e);
            return new ArrayList<>();
        }
    }

    // 加载文本文件 (txt, md)
    private List<Document> loadTextDocuments(ResourcePatternResolver resourceResolver) throws IOException {
        List<Document> documents = new ArrayList<>();
        
        // 使用classpath模式匹配加载所有txt和md文件
        Resource[] resources = resourceResolver.getResources("classpath*:content/**/*.txt");
        Resource[] mdResources = resourceResolver.getResources("classpath*:content/**/*.md");
        
        // 合并所有资源
        List<Resource> allResources = new ArrayList<>();
        allResources.addAll(Arrays.asList(resources));
        allResources.addAll(Arrays.asList(mdResources));

        for (Resource resource : allResources) {
            try {
                String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                Document document = Document.from(content);
                documents.add(document);
                log.debug("加载文本文档: {}", resource.getFilename());
            } catch (IOException e) {
                log.warn("无法读取文本文档: {}", resource.getFilename(), e);
            }
        }

        return documents;
    }

    // 加载PDF文件
    private List<Document> loadPdfDocuments(ResourcePatternResolver resourceResolver) throws IOException {
        List<Document> documents = new ArrayList<>();
        ApachePdfBoxDocumentParser pdfParser = new ApachePdfBoxDocumentParser();

        // 使用classpath模式匹配加载所有pdf文件
        Resource[] resources = resourceResolver.getResources("classpath*:content/**/*.pdf");

        for (Resource resource : resources) {
            try {
                Document document = pdfParser.parse(resource.getInputStream());
                documents.add(document);
                log.debug("加载PDF文档: {}", resource.getFilename());
            } catch (Exception e) {
                log.warn("无法解析PDF文档: {}", resource.getFilename(), e);
            }
        }

        return documents;
    }
}
