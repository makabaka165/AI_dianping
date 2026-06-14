package com.hmdp.service.impl;

import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.ai.infra.DocumentQualityAssessor;
import com.hmdp.service.DocumentManagementService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DocumentManagementServiceImpl implements DocumentManagementService {

    // 使用内存存储文档元数据，实际项目中应该使用数据库存储
    private final Map<String, DocumentMetadata> documentMetadataStore = new ConcurrentHashMap<>();
    private final Map<String, Document> documentStore = new ConcurrentHashMap<>();

    @Autowired
    private DocumentQualityAssessor qualityAssessor;

    // 移除了对EmbeddingStore的直接依赖，避免循环依赖问题

    @PostConstruct
    public void init() {
        log.info("文档管理服务初始化完成");
    }

    @Override
    public void saveDocument(DocumentMetadata metadata) {
        if (metadata.getId() == null || metadata.getId().isEmpty()) {
            metadata.setId(UUID.randomUUID().toString());
        }
        
        if (metadata.getCreatedAt() == null) {
            metadata.setCreatedAt(LocalDateTime.now());
        }
        metadata.setUpdatedAt(LocalDateTime.now());
        
        if (metadata.getStatus() == null) {
            metadata.setStatus(DocumentStatus.PUBLISHED);
        }
        
        documentMetadataStore.put(metadata.getId(), metadata);
        log.info("保存文档元数据: ID={}, 标题={}", metadata.getId(), metadata.getTitle());
    }

    @Override
    public String addDocument(Document document, DocumentMetadata metadata) {
        String documentId = UUID.randomUUID().toString();
        
        // 如果没有提供metadata，创建默认的
        if (metadata == null) {
            metadata = new DocumentMetadata();
            metadata.setId(documentId);
            metadata.setTitle("未命名文档");
            metadata.setSource("系统添加");
            metadata.setFileType("txt");
            metadata.setCreatedAt(LocalDateTime.now());
            metadata.setUpdatedAt(LocalDateTime.now());
            metadata.setStatus(DocumentStatus.PUBLISHED);
        } else {
            metadata.setId(documentId);
            if (metadata.getCreatedAt() == null) {
                metadata.setCreatedAt(LocalDateTime.now());
            }
            metadata.setUpdatedAt(LocalDateTime.now());
            if (metadata.getStatus() == null) {
                metadata.setStatus(DocumentStatus.PUBLISHED);
            }
        }

        // 评估文档质量
        double qualityScore = qualityAssessor.assessQuality(document);
        metadata.setQualityScore(qualityScore);
        
        // 设置词数
        metadata.setWordCount(document.text().length());
        
        // 存储文档和元数据
        documentMetadataStore.put(documentId, metadata);
        documentStore.put(documentId, document);
        
        log.info("添加文档: ID={}, 标题={}, 质量得分={}", documentId, metadata.getTitle(), qualityScore);
        return documentId;
    }

    @Override
    public boolean updateDocument(String documentId, Document document, DocumentMetadata metadata) {
        if (!documentMetadataStore.containsKey(documentId)) {
            log.warn("尝试更新不存在的文档: {}", documentId);
            return false;
        }

        // 更新元数据
        if (metadata != null) {
            DocumentMetadata existingMetadata = documentMetadataStore.get(documentId);
            // 保留一些不变的字段
            metadata.setId(documentId);
            metadata.setCreatedAt(existingMetadata.getCreatedAt());
            if (metadata.getCreatedAt() == null) {
                metadata.setCreatedAt(existingMetadata.getCreatedAt());
            }
            metadata.setUpdatedAt(LocalDateTime.now());
            if (metadata.getStatus() == null) {
                metadata.setStatus(existingMetadata.getStatus());
            }
            
            // 重新评估质量
            double qualityScore = qualityAssessor.assessQuality(document);
            metadata.setQualityScore(qualityScore);
            metadata.setWordCount(document.text().length());
            
            documentMetadataStore.put(documentId, metadata);
        }

        // 更新文档内容
        if (document != null) {
            documentStore.put(documentId, document);
        }

        log.info("更新文档: {}", documentId);
        return true;
    }

    @Override
    public boolean deleteDocument(String documentId) {
        DocumentMetadata removedMetadata = documentMetadataStore.remove(documentId);
        Document removedDocument = documentStore.remove(documentId);
        
        boolean success = removedMetadata != null && removedDocument != null;
        if (success) {
            log.info("删除文档: {}", documentId);
        } else {
            log.warn("尝试删除不存在的文档: {}", documentId);
        }
        
        return success;
    }

    @Override
    public Optional<DocumentMetadata> getDocumentMetadata(String documentId) {
        return Optional.ofNullable(documentMetadataStore.get(documentId));
    }

    @Override
    public Optional<Document> getDocument(String documentId) {
        return Optional.ofNullable(documentStore.get(documentId));
    }

    @Override
    public List<DocumentMetadata> listAllDocuments() {
        return new ArrayList<>(documentMetadataStore.values());
    }

    @Override
    public List<DocumentMetadata> listDocumentsByStatus(DocumentStatus status) {
        return documentMetadataStore.values().stream()
                .filter(metadata -> metadata.getStatus() == status)
                .collect(ArrayList::new, (list, item) -> list.add(item), ArrayList::addAll);
    }

    @Override
    public List<DocumentMetadata> listDocumentsByQualityScoreRange(double minScore, double maxScore) {
        return documentMetadataStore.values().stream()
                .filter(metadata -> metadata.getQualityScore() >= minScore && metadata.getQualityScore() <= maxScore)
                .collect(ArrayList::new, (list, item) -> list.add(item), ArrayList::addAll);
    }
}
