package com.hmdp.config;

import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.repository.RedissonChatMemoryStore;
import com.hmdp.service.DocumentManagementService;
import com.hmdp.service.impl.DocumentQualityAssessor;
import com.hmdp.service.impl.QualityBasedContentRetriever;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
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
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
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
public class CommonAIConfig {

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

    // ========== Redis向量数据库配置 ==========
    @Value("${rag.redis.host:localhost}")
    private String redisHost;

    @Value("${rag.redis.port:6380}")
    private int redisPort;

    @Value("${rag.redis.dimension:1536}")
    private int vectorDimension;

    @Value("${rag.platform-policy.index-name:platform_policy_kb}")
    private String platformPolicyIndexName;

    @Value("${rag.platform-policy.dimension:${rag.redis.dimension:1536}}")
    private int platformPolicyDimension;

    @Value("${rag.review.index-name:shop_review_kb}")
    private String reviewIndexName;

    @Value("${rag.review.dimension:${rag.redis.dimension:1536}}")
    private int reviewDimension;

    @Value("${rag.enabled:false}")
    private boolean ragEnabled;

    @Value("${rag.data.auto-import:false}")
    private boolean autoImportData;

    @Value("${rag.redis.fallback-to-memory:false}")
    private boolean ragRedisFallbackToMemory;

    @Value("${hmdp.ai.redis-health-check:true}")
    private boolean redisHealthCheckEnabled;

    @Value("${hmdp.ai.redisson-fallback:false}")
    private boolean redissonFallbackEnabled;

    // 注入ApplicationContext
    @Autowired
    private ApplicationContext applicationContext;

    private final AtomicBoolean platformPolicyImportAttempted = new AtomicBoolean(false);

    // ========== Redis配置 ==========

    @Bean
    public RedissonClient redissonClient() {
        log.info("初始化 RedissonClient - 聊天记忆专用（6379端口）");
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        try {
            return Redisson.create(config);
        } catch (RuntimeException e) {
            if (!redissonFallbackEnabled) {
                throw e;
            }
            log.warn("RedissonClient create failed, using no-op fallback because hmdp.ai.redisson-fallback=true", e);
            return noOpProxy(RedissonClient.class);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T noOpProxy(Class<T> type) {
        InvocationHandler handler = (proxy, method, args) -> {
            String methodName = method.getName();
            if ("toString".equals(methodName)) {
                return "NoOp" + type.getSimpleName();
            }
            if ("tryLock".equals(methodName)) {
                return true;
            }
            if ("isHeldByCurrentThread".equals(methodName)) {
                return true;
            }
            if ("isShutdown".equals(methodName) || "isShuttingDown".equals(methodName)) {
                return false;
            }
            if ("delete".equals(methodName)) {
                return true;
            }
            if ("getKeysByPattern".equals(methodName)) {
                return Collections.emptyList();
            }

            Class<?> returnType = method.getReturnType();
            if (Void.TYPE.equals(returnType)) {
                return null;
            }
            if (Boolean.TYPE.equals(returnType)) {
                return false;
            }
            if (Integer.TYPE.equals(returnType) || Long.TYPE.equals(returnType)
                    || Short.TYPE.equals(returnType) || Byte.TYPE.equals(returnType)) {
                return 0;
            }
            if (Double.TYPE.equals(returnType) || Float.TYPE.equals(returnType)) {
                return 0D;
            }
            if (Iterable.class.isAssignableFrom(returnType)) {
                return Collections.emptyList();
            }
            if (returnType.isInterface()) {
                return noOpProxy((Class<Object>) returnType);
            }
            return null;
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }


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
     * ???? Redis ?????????? FAQ ?????????????????? RAG ???
     */
    private RedisEmbeddingStore buildRedisEmbeddingStore(String indexName, int dimension) {
        log.info("??? RedisEmbeddingStore - RAG?? - ??: {}, ??: {}, ??: {}, ??: {}",
                redisHost, redisPort, indexName, dimension);

        RedisEmbeddingStore store = RedisEmbeddingStore.builder()
                .host(redisHost)
                .port(redisPort)
                .indexName(indexName)
                .dimension(dimension)
                .build();
        log.info("RedisEmbeddingStore ????, index={}", indexName);
        return store;
    }

    @Bean("platformPolicyInMemoryEmbeddingStore")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true", matchIfMissing = false)
    public InMemoryEmbeddingStore<TextSegment> platformPolicyInMemoryEmbeddingStore() {
        log.info("??????? InMemoryEmbeddingStore - ????");
        return new InMemoryEmbeddingStore<>();
    }

    @Bean("shopReviewInMemoryEmbeddingStore")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true", matchIfMissing = false)
    public InMemoryEmbeddingStore<TextSegment> shopReviewInMemoryEmbeddingStore() {
        log.info("??????? InMemoryEmbeddingStore - ????");
        return new InMemoryEmbeddingStore<>();
    }

    @Bean("platformPolicyEmbeddingStore")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true")
    public EmbeddingStore<TextSegment> platformPolicyEmbeddingStore(
            @Qualifier("platformPolicyInMemoryEmbeddingStore")
            InMemoryEmbeddingStore<TextSegment> platformPolicyInMemoryEmbeddingStore,
            EmbeddingModel embeddingModel) {

        log.info("??????????...");
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

        log.info("??????????...");
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
            DocumentQualityAssessor documentQualityAssessor) {

        log.info("??????? ContentRetriever?????: 0.5, ?????: 5");
        ContentRetriever delegate = QualityBasedContentRetriever.builder()
                .embeddingStore(platformPolicyEmbeddingStore)
                .embeddingModel(embeddingModel)
                .documentManagementService(null)
                .minScore(0.5)
                .maxResults(5)
                .build();
        return query -> {
            initializePlatformPolicyStoreIfNeeded(platformPolicyEmbeddingStore, embeddingModel, documentQualityAssessor);
            return delegate.retrieve(query);
        };
    }

    @Bean("platformPolicyContentRetriever")
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "false", matchIfMissing = true)
    public ContentRetriever noopPlatformPolicyContentRetriever() {
        log.info("rag.enabled=false, ???????? ContentRetriever");
        return query -> Collections.emptyList();
    }


    // ========== 工具类配置 ==========
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }


    //redis stack验证配置
    @PostConstruct
    public void validateRedisConnections() {
        if (!redisHealthCheckEnabled) {
            log.info("skip Redis connection validation because hmdp.ai.redis-health-check=false");
            return;
        }
        log.info("🔍 验证Redis连接配置...");

        // 验证聊天记忆Redis（6379）
        try {
            redissonClient().getBucket("health-check-session").set("ok");
            log.info("✅ 聊天记忆Redis (6379) 连接正常");
        } catch (Exception e) {
            log.error("❌ 聊天记忆Redis (6379) 连接失败", e);
        }

        // 验证RAG Redis Stack（6380）
        if (ragEnabled) {
            try {
                // 这个会在redisEmbeddingStore创建时自动验证
                log.info("✅ RAG Redis Stack (6380) 配置正常");
            } catch (Exception e) {
                log.error("❌ RAG Redis Stack (6380) 连接失败", e);
                log.warn("⚠️  RAG功能将不可用，请确保Redis在端口6380上运行");
            }
        }

        log.info("🎯 双Redis配置验证完成！");
    }


    // ========== 向量数据库初始化逻辑 ==========

    /**
     * 检查是否需要初始化向量存储
     */
    /**
     * 初始化向量存储数据。Embedding API 抖动只告警，不阻塞应用启动。
     */
    private void initializeVectorStore(EmbeddingStore<TextSegment> embeddingStore, 
                                      EmbeddingModel embeddingModel,
                                      DocumentQualityAssessor documentQualityAssessor) {
        try {
            // 1. 加载文档
            List<Document> documents = loadDocuments();

            if (documents.isEmpty()) {
                log.warn("未找到任何文档用于初始化向量数据库");
                return;
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
                // 创建文档元数据
                DocumentMetadata metadata = new DocumentMetadata();
                metadata.setTitle("导入文档 " + UUID.randomUUID().toString().substring(0, 8));
                metadata.setSource("系统初始化导入");
                metadata.setFileType("txt");
                metadata.setCreatedAt(LocalDateTime.now());
                metadata.setUpdatedAt(LocalDateTime.now());
                metadata.setStatus(DocumentStatus.PUBLISHED);
                
                // 评估文档质量
                double qualityScore = documentQualityAssessor.assessQuality(document);
                metadata.setQualityScore(qualityScore);
                metadata.setWordCount(document.text().length());
                
                // 获取文档管理服务并保存文档
                DocumentManagementService documentManagementService = (DocumentManagementService) applicationContext.getBean("documentManagementServiceImpl");
                documentManagementService.saveDocument(metadata);
            }

            log.info("✅ 成功将 {} 个文档导入向量数据库！", documents.size());

        } catch (Exception e) {
            log.warn("Platform policy vector import skipped because embedding service is unavailable or import failed: {}",
                    e.getMessage(), e);
        }
    }

    private void initializePlatformPolicyStoreIfNeeded(EmbeddingStore<TextSegment> embeddingStore,
                                                       EmbeddingModel embeddingModel,
                                                       DocumentQualityAssessor documentQualityAssessor) {
        if (!autoImportData) {
            return;
        }
        if (!platformPolicyImportAttempted.compareAndSet(false, true)) {
            return;
        }
        initializeVectorStore(embeddingStore, embeddingModel, documentQualityAssessor);
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
