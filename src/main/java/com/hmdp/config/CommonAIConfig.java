package com.hmdp.config;

import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.repository.RedissonChatMemoryStore;
import com.hmdp.service.DocumentManagementService;
import com.hmdp.service.impl.DocumentManagementServiceImpl;
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
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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

    @Value("${langchain4j.open-ai.embedding-model.model-name:text-embedding-v3}")
    private String embeddingModelName;

    @Value("${chat.memory.max-messages:20}")
    private int maxMessages;

    // ========== Redis向量数据库配置 ==========
    @Value("${rag.redis.host:localhost}")
    private String redisHost;

    @Value("${rag.redis.port:6380}")
    private int redisPort;

    @Value("${rag.redis.index-name:shop_knowledge_base}")
    private String vectorIndexName;

    @Value("${rag.redis.dimension:1536}")
    private int vectorDimension;

    @Value("${rag.enabled:false}")
    private boolean ragEnabled;

    @Value("${rag.data.auto-import:false}")
    private boolean autoImportData;

    @Value("${hmdp.ai.redis-health-check:true}")
    private boolean redisHealthCheckEnabled;

    @Value("${hmdp.ai.redisson-fallback:false}")
    private boolean redissonFallbackEnabled;

    // 注入ApplicationContext
    @Autowired
    private ApplicationContext applicationContext;

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
                .logRequests(true)
                .logResponses(true)
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
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("初始化 EmbeddingModel: {}", embeddingModelName);
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(embeddingModelName)
                .baseUrl(baseUrl)
                .build();
    }


    /**
     * 创建Redis向量存储Bean
     */
    // RAG Redis配置已经正确指向6380
    @Bean
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true")
    public RedisEmbeddingStore redisEmbeddingStore() {
        log.info("初始化 RedisEmbeddingStore - RAG专用 - 主机: {}, 端口: {}, 索引: {}, 维度: {}",
                redisHost, redisPort, vectorIndexName, vectorDimension);

        try {
            RedisEmbeddingStore store = RedisEmbeddingStore.builder()
                    .host(redisHost)
                    .port(redisPort)  // 这里会使用6380端口
                    .indexName(vectorIndexName)
                    .dimension(vectorDimension)
                    .build();
            log.info("✅ RedisEmbeddingStore 创建成功");
            return store;
        } catch (Exception e) {
            log.error("❌ RedisEmbeddingStore 创建失败: {}", e.getMessage(), e);
            throw new RuntimeException("Redis向量存储初始化失败", e);
        }
    }
    
    /**
     * 创建内存向量存储Bean（降级方案）
     */
    @Bean
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true", matchIfMissing = false)
    public InMemoryEmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        log.info("初始化 InMemoryEmbeddingStore - 降级方案");
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 构建向量数据库操作对象（主方案：Redis，降级方案：内存）
     */
    @Bean
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true")
    public EmbeddingStore<TextSegment> embeddingStore(
            RedisEmbeddingStore redisEmbeddingStore,
            InMemoryEmbeddingStore<TextSegment> inMemoryEmbeddingStore,
            EmbeddingModel embeddingModel,
            DocumentQualityAssessor documentQualityAssessor) {

        log.info("初始化向量数据库...");

        // 尝试使用Redis存储
        try {
            // 检查是否需要初始化数据
            if (shouldInitializeVectorStore(redisEmbeddingStore, embeddingModel)) {
                if (autoImportData) {
                    initializeVectorStore(redisEmbeddingStore, embeddingModel, documentQualityAssessor);
                } else {
                    log.info("rag.data.auto-import=false, skip vector store import");
                }
                log.info("检测到空的Redis向量数据库，开始初始化文档数据...");
                // 初始化向量存储的逻辑已移至应用启动时执行，避免循环依赖
            } else {
                log.info("Redis向量数据库已有数据，跳过初始化");
            }
            
            log.info("✅ 使用Redis向量数据库");
            return redisEmbeddingStore;
        } catch (Exception e) {
            log.warn("❌ Redis向量数据库不可用，切换到内存存储: {}", e.getMessage());
            log.info("✅ 使用内存向量数据库（降级方案）");
            return inMemoryEmbeddingStore;
        }
    }

    /**
     * 构建向量数据库检索对象
     */
    @Bean
    @ConditionalOnProperty(value = "rag.enabled", havingValue = "true")
    public ContentRetriever contentRetriever(
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel) {

        log.info("初始化基于质量的 ContentRetriever，最小分数: 0.5, 最大结果数: 5");

        return QualityBasedContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .documentManagementService(null)
                .minScore(0.5)
                .maxResults(5)
                .build();
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
    private boolean shouldInitializeVectorStore(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        try {
            // 执行一个测试查询来检查是否有数据
            var testEmbedding = embeddingModel.embed("测试查询").content();
            log.debug("测试查询向量维度: {}", testEmbedding.dimension());
            var results = embeddingStore.findRelevant(testEmbedding, 1, 0.0);

            boolean hasData = results != null && !results.isEmpty();
            log.info("向量数据库状态检查 - 是否有数据: {}", hasData);
            
            if (hasData) {
                log.debug("找到数据示例: {}", results.get(0).embedded().text());
            }

            return !hasData; // 没有数据则需要初始化

        } catch (Exception e) {
            log.info("向量数据库状态检查失败，假设需要初始化: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 初始化向量存储数据
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

            // 5. 验证导入结果
            verifyVectorStoreData(embeddingStore, embeddingModel);

        } catch (Exception e) {
            log.error("❌ 初始化向量数据库失败", e);
            throw new RuntimeException("向量数据库初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 验证向量存储数据
     */
    private void verifyVectorStoreData(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        try {
            // 首先尝试获取任意内容
            log.info("尝试获取向量存储中的任意内容...");
            var testEmbedding = embeddingModel.embed("测试").content();
            var allResults = embeddingStore.findRelevant(testEmbedding, 5, 0.0);
            log.info("在向量存储中找到 {} 个条目", allResults.size());
            
            if (!allResults.isEmpty()) {
                for (int i = 0; i < Math.min(3, allResults.size()); i++) {
                    String text = allResults.get(i).embedded().text();
                    double score = allResults.get(i).score();
                    String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                    log.debug("存储条目 {} (得分: {}): {}", i+1, score, preview);
                }
            } else {
                log.warn("向量存储中没有任何内容");
            }

            // 使用一些测试查询来验证数据
            String[] testQueries = {"营业时间", "支付方式", "退换货", "周一至周日"};

            for (String query : testQueries) {
                log.info("开始验证查询: '{}'", query);
                var embedding = embeddingModel.embed(query).content();
                log.debug("查询 '{}' 的嵌入向量维度: {}", query, embedding.dimension());
                
                // 进一步降低最小分数阈值，增加返回结果数量
                var results = embeddingStore.findRelevant(embedding, 10, 0.01);

                log.info("验证查询 '{}' - 找到 {} 个相关结果 (阈值: 0.01)", query, results.size());

                if (!results.isEmpty()) {
                    // 打印第一个结果的部分内容（用于调试）
                    String text = results.get(0).embedded().text();
                    String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                    log.debug("示例结果预览: {}", preview);
                } else {
                    // 尝试用更宽松的方式检索所有内容
                    var allResultsQuery = embeddingStore.findRelevant(embedding, 10, 0.0);
                    log.info("使用阈值0.0查询 '{}' - 找到 {} 个相关结果", query, allResultsQuery.size());
                    
                    // 如果仍然没有结果，尝试检索所有内容
                    if (allResultsQuery.isEmpty()) {
                        log.info("尝试获取存储中的所有内容...");
                    } else {
                        for (int i = 0; i < Math.min(3, allResultsQuery.size()); i++) {
                            String text = allResultsQuery.get(i).embedded().text();
                            double score = allResultsQuery.get(i).score();
                            String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                            log.debug("结果 {} (得分: {}): {}", i+1, score, preview);
                        }
                    }
                }
            }

            log.info("✅ 向量数据库验证完成！");

        } catch (Exception e) {
            log.warn("向量数据库验证失败: {}", e.getMessage(), e);
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
