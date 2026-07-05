package com.hmdp.service;

import com.hmdp.entity.DocumentMetadata;
import dev.langchain4j.data.document.Document;

import java.util.List;
import java.util.Optional;

/**
 * 文档管理服务接口
 */
public interface DocumentManagementService {
    /**
     * 添加新文档
     */
    String addDocument(Document document, DocumentMetadata metadata);

    /**
     * Persist an imported document without re-ingesting it into the vector store.
     */
    void saveDocument(Document document, DocumentMetadata metadata);

    /**
     * 更新文档
     */
    boolean updateDocument(String documentId, Document document, DocumentMetadata metadata);

    /**
     * 删除文档
     */
    boolean deleteDocument(String documentId);

    /**
     * 获取文档元数据
     */
    Optional<DocumentMetadata> getDocumentMetadata(String documentId);

    /**
     * 获取文档
     */
    Optional<Document> getDocument(String documentId);

    /**
     * 列出所有文档元数据
     */
    List<DocumentMetadata> listAllDocuments();

    /**
     * 根据状态列出文档
     */
    List<DocumentMetadata> listDocumentsByStatus(com.hmdp.entity.DocumentStatus status);

    /**
     * 根据质量评分范围列出文档
     */
    List<DocumentMetadata> listDocumentsByQualityScoreRange(double minScore, double maxScore);

    /**
     * 保存文档元数据
     * @param metadata 文档元数据
     */
    void saveDocument(DocumentMetadata metadata);
}
