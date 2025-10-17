package com.hmdp.tools;

import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.service.impl.DocumentManagementServiceImpl;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DocumentManagementTool {

    @Autowired
    private DocumentManagementServiceImpl documentManagementService;

    @Tool("列出所有文档的元数据信息")
    public String listAllDocuments() {
        try {
            log.info("🔧 Tool被调用: listAllDocuments");

            List<DocumentMetadata> documents = documentManagementService.listAllDocuments();
            
            if (documents.isEmpty()) {
                return "当前系统中没有文档。";
            }

            StringBuilder result = new StringBuilder();
            result.append("📚 文档列表:\n\n");
            
            for (int i = 0; i < documents.size(); i++) {
                DocumentMetadata doc = documents.get(i);
                result.append(String.format("%d. %s\n", i + 1, doc.getTitle()));
                result.append(String.format("   ID: %s\n", doc.getId()));
                result.append(String.format("   来源: %s\n", doc.getSource()));
                result.append(String.format("   类型: %s\n", doc.getFileType()));
                result.append(String.format("   状态: %s\n", doc.getStatus().getDescription()));
                result.append(String.format("   质量评分: %.2f\n", doc.getQualityScore()));
                result.append(String.format("   词数: %d\n", doc.getWordCount()));
                result.append(String.format("   创建时间: %s\n", doc.getCreatedAt()));
                result.append("\n");
            }

            log.info("✅ Tool调用成功: listAllDocuments, 返回 {} 个文档", documents.size());
            return result.toString();

        } catch (Exception e) {
            log.error("❌ Tool调用失败: listAllDocuments", e);
            return "获取文档列表时出现错误，请稍后重试。";
        }
    }

    @Tool("根据质量评分范围查找文档")
    public String listDocumentsByQualityScore(
            @P("最小质量评分 (0-1)") double minScore,
            @P("最大质量评分 (0-1)") double maxScore) {
        try {
            log.info("🔧 Tool被调用: listDocumentsByQualityScore, 范围: {} - {}", minScore, maxScore);

            List<DocumentMetadata> documents = documentManagementService.listDocumentsByQualityScoreRange(minScore, maxScore);
            
            if (documents.isEmpty()) {
                return String.format("没有质量评分在 %.2f - %.2f 范围内的文档。", minScore, maxScore);
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("🔍 质量评分在 %.2f - %.2f 范围内的文档:\n\n", minScore, maxScore));
            
            for (int i = 0; i < documents.size(); i++) {
                DocumentMetadata doc = documents.get(i);
                result.append(String.format("%d. %s (质量评分: %.2f)\n", i + 1, doc.getTitle(), doc.getQualityScore()));
            }

            log.info("✅ Tool调用成功: listDocumentsByQualityScore, 返回 {} 个文档", documents.size());
            return result.toString();

        } catch (Exception e) {
            log.error("❌ Tool调用失败: listDocumentsByQualityScore", e);
            return "根据质量评分查找文档时出现错误，请稍后重试。";
        }
    }

    @Tool("根据状态查找文档")
    public String listDocumentsByStatus(@P("文档状态 (DRAFT, PUBLISHED, ARCHIVED, DELETED)") String status) {
        try {
            log.info("🔧 Tool被调用: listDocumentsByStatus, 状态: {}", status);

            DocumentStatus documentStatus;
            try {
                documentStatus = DocumentStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                return "无效的文档状态。有效的状态包括: DRAFT, PUBLISHED, ARCHIVED, DELETED";
            }

            List<DocumentMetadata> documents = documentManagementService.listDocumentsByStatus(documentStatus);
            
            if (documents.isEmpty()) {
                return String.format("没有状态为 %s 的文档。", documentStatus.getDescription());
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("📂 状态为 %s 的文档:\n\n", documentStatus.getDescription()));
            
            for (int i = 0; i < documents.size(); i++) {
                DocumentMetadata doc = documents.get(i);
                result.append(String.format("%d. %s\n", i + 1, doc.getTitle()));
            }

            log.info("✅ Tool调用成功: listDocumentsByStatus, 返回 {} 个文档", documents.size());
            return result.toString();

        } catch (Exception e) {
            log.error("❌ Tool调用失败: listDocumentsByStatus", e);
            return "根据状态查找文档时出现错误，请稍后重试。";
        }
    }

    @Tool("获取文档详细信息")
    public String getDocumentDetails(@P("文档ID") String documentId) {
        try {
            log.info("🔧 Tool被调用: getDocumentDetails, 文档ID: {}", documentId);

            Optional<DocumentMetadata> metadataOpt = documentManagementService.getDocumentMetadata(documentId);
            
            if (!metadataOpt.isPresent()) {
                return String.format("未找到ID为 %s 的文档。", documentId);
            }

            DocumentMetadata metadata = metadataOpt.get();
            StringBuilder result = new StringBuilder();
            result.append("📄 文档详细信息:\n\n");
            result.append(String.format("标题: %s\n", metadata.getTitle()));
            result.append(String.format("ID: %s\n", metadata.getId()));
            result.append(String.format("来源: %s\n", metadata.getSource()));
            result.append(String.format("类型: %s\n", metadata.getFileType()));
            result.append(String.format("状态: %s\n", metadata.getStatus().getDescription()));
            result.append(String.format("质量评分: %.2f\n", metadata.getQualityScore()));
            result.append(String.format("词数: %d\n", metadata.getWordCount()));
            result.append(String.format("创建时间: %s\n", metadata.getCreatedAt()));
            result.append(String.format("更新时间: %s\n", metadata.getUpdatedAt()));
            
            if (metadata.getKeywords() != null && metadata.getKeywords().length > 0) {
                result.append(String.format("关键词: %s\n", String.join(", ", metadata.getKeywords())));
            }

            log.info("✅ Tool调用成功: getDocumentDetails");
            return result.toString();

        } catch (Exception e) {
            log.error("❌ Tool调用失败: getDocumentDetails, 文档ID: {}", documentId, e);
            return "获取文档详细信息时出现错误，请稍后重试。";
        }
    }

    @Tool("获取系统中文档统计信息")
    public String getDocumentStatistics() {
        try {
            log.info("🔧 Tool被调用: getDocumentStatistics");

            List<DocumentMetadata> allDocuments = documentManagementService.listAllDocuments();
            
            if (allDocuments.isEmpty()) {
                return "当前系统中没有文档。";
            }

            long totalDocs = allDocuments.size();
            double avgQuality = allDocuments.stream().mapToDouble(DocumentMetadata::getQualityScore).average().orElse(0.0);
            long highQualityDocs = allDocuments.stream().filter(doc -> doc.getQualityScore() >= 0.8).count();
            long mediumQualityDocs = allDocuments.stream().filter(doc -> doc.getQualityScore() >= 0.5 && doc.getQualityScore() < 0.8).count();
            long lowQualityDocs = allDocuments.stream().filter(doc -> doc.getQualityScore() < 0.5).count();

            StringBuilder result = new StringBuilder();
            result.append("📊 文档统计信息:\n\n");
            result.append(String.format("总文档数: %d\n", totalDocs));
            result.append(String.format("平均质量评分: %.2f\n", avgQuality));
            result.append(String.format("高质量文档 (≥0.8): %d\n", highQualityDocs));
            result.append(String.format("中等质量文档 (0.5-0.8): %d\n", mediumQualityDocs));
            result.append(String.format("低质量文档 (<0.5): %d\n", lowQualityDocs));

            // 按状态统计
            result.append("\n按状态统计:\n");
            for (DocumentStatus status : DocumentStatus.values()) {
                long count = allDocuments.stream().filter(doc -> doc.getStatus() == status).count();
                result.append(String.format("  %s: %d\n", status.getDescription(), count));
            }

            log.info("✅ Tool调用成功: getDocumentStatistics");
            return result.toString();

        } catch (Exception e) {
            log.error("❌ Tool调用失败: getDocumentStatistics", e);
            return "获取文档统计信息时出现错误，请稍后重试。";
        }
    }
}