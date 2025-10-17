package com.hmdp.controller;

import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.service.impl.DocumentManagementServiceImpl;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/document")
public class DocumentManagementController {

    @Autowired
    private DocumentManagementServiceImpl documentManagementService;

    /**
     * 列出所有文档
     */
    @GetMapping("/list")
    public Result listAllDocuments() {
        try {
            List<DocumentMetadata> documents = documentManagementService.listAllDocuments();
            return Result.ok(documents);
        } catch (Exception e) {
            log.error("获取文档列表失败", e);
            return Result.fail("获取文档列表失败");
        }
    }

    /**
     * 根据ID获取文档详情
     */
    @GetMapping("/{documentId}")
    public Result getDocument(@PathVariable String documentId) {
        try {
            Optional<DocumentMetadata> metadata = documentManagementService.getDocumentMetadata(documentId);
            if (metadata.isPresent()) {
                return Result.ok(metadata.get());
            } else {
                return Result.fail("文档不存在");
            }
        } catch (Exception e) {
            log.error("获取文档详情失败", e);
            return Result.fail("获取文档详情失败");
        }
    }

    /**
     * 根据状态列出文档
     */
    @GetMapping("/status/{status}")
    public Result listDocumentsByStatus(@PathVariable String status) {
        try {
            DocumentStatus documentStatus = DocumentStatus.valueOf(status.toUpperCase());
            List<DocumentMetadata> documents = documentManagementService.listDocumentsByStatus(documentStatus);
            return Result.ok(documents);
        } catch (IllegalArgumentException e) {
            return Result.fail("无效的文档状态");
        } catch (Exception e) {
            log.error("根据状态获取文档列表失败", e);
            return Result.fail("获取文档列表失败");
        }
    }

    /**
     * 根据质量评分范围列出文档
     */
    @GetMapping("/quality")
    public Result listDocumentsByQualityScoreRange(@RequestParam double minScore, @RequestParam double maxScore) {
        try {
            List<DocumentMetadata> documents = documentManagementService.listDocumentsByQualityScoreRange(minScore, maxScore);
            return Result.ok(documents);
        } catch (Exception e) {
            log.error("根据质量评分获取文档列表失败", e);
            return Result.fail("获取文档列表失败");
        }
    }

    /**
     * 获取文档统计信息
     */
    @GetMapping("/statistics")
    public Result getStatistics() {
        try {
            List<DocumentMetadata> allDocuments = documentManagementService.listAllDocuments();
            
            long totalDocs = allDocuments.size();
            double avgQuality = allDocuments.stream().mapToDouble(DocumentMetadata::getQualityScore).average().orElse(0.0);
            long highQualityDocs = allDocuments.stream().filter(doc -> doc.getQualityScore() >= 0.8).count();
            long mediumQualityDocs = allDocuments.stream().filter(doc -> doc.getQualityScore() >= 0.5 && doc.getQualityScore() < 0.8).count();
            long lowQualityDocs = allDocuments.stream().filter(doc -> doc.getQualityScore() < 0.5).count();

            // 按状态统计
            long draftCount = allDocuments.stream().filter(doc -> doc.getStatus() == DocumentStatus.DRAFT).count();
            long publishedCount = allDocuments.stream().filter(doc -> doc.getStatus() == DocumentStatus.PUBLISHED).count();
            long archivedCount = allDocuments.stream().filter(doc -> doc.getStatus() == DocumentStatus.ARCHIVED).count();
            long deletedCount = allDocuments.stream().filter(doc -> doc.getStatus() == DocumentStatus.DELETED).count();

            return Result.ok(new DocumentStatistics(
                totalDocs, avgQuality, highQualityDocs, mediumQualityDocs, lowQualityDocs,
                draftCount, publishedCount, archivedCount, deletedCount
            ));
        } catch (Exception e) {
            log.error("获取文档统计信息失败", e);
            return Result.fail("获取文档统计信息失败");
        }
    }

    /**
     * 上传新文档
     */
    @PostMapping("/upload")
    public Result uploadDocument(@RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) String title,
                                 @RequestParam(required = false) String source) {
        try {
            if (file.isEmpty()) {
                return Result.fail("上传文件不能为空");
            }

            // 读取文件内容
            String content = new String(file.getBytes());
            dev.langchain4j.data.document.Document document = dev.langchain4j.data.document.Document.from(content);

            // 创建元数据
            DocumentMetadata metadata = new DocumentMetadata();
            metadata.setTitle(title != null ? title : file.getOriginalFilename());
            metadata.setSource(source != null ? source : "手动上传");
            metadata.setFileType(getFileType(file.getOriginalFilename()));
            metadata.setCreatedAt(LocalDateTime.now());
            metadata.setUpdatedAt(LocalDateTime.now());
            metadata.setStatus(DocumentStatus.PUBLISHED);

            // 添加文档
            String documentId = documentManagementService.addDocument(document, metadata);

            return Result.ok("文档上传成功，ID: " + documentId);
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            return Result.fail("读取上传文件失败");
        } catch (Exception e) {
            log.error("上传文档失败", e);
            return Result.fail("上传文档失败");
        }
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{documentId}")
    public Result deleteDocument(@PathVariable String documentId) {
        try {
            boolean success = documentManagementService.deleteDocument(documentId);
            if (success) {
                return Result.ok("文档删除成功");
            } else {
                return Result.fail("文档不存在");
            }
        } catch (Exception e) {
            log.error("删除文档失败", e);
            return Result.fail("删除文档失败");
        }
    }

    /**
     * 根据文件名获取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null) {
            return "txt";
        }
        
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < filename.length() - 1) {
            return filename.substring(lastDotIndex + 1).toLowerCase();
        }
        
        return "txt";
    }

    /**
     * 文档统计信息内部类
     */
    public static class DocumentStatistics {
        private long totalDocuments;
        private double averageQualityScore;
        private long highQualityDocuments;
        private long mediumQualityDocuments;
        private long lowQualityDocuments;
        private long draftDocuments;
        private long publishedDocuments;
        private long archivedDocuments;
        private long deletedDocuments;

        public DocumentStatistics(long totalDocuments, double averageQualityScore, 
                                long highQualityDocuments, long mediumQualityDocuments, long lowQualityDocuments,
                                long draftDocuments, long publishedDocuments, long archivedDocuments, long deletedDocuments) {
            this.totalDocuments = totalDocuments;
            this.averageQualityScore = averageQualityScore;
            this.highQualityDocuments = highQualityDocuments;
            this.mediumQualityDocuments = mediumQualityDocuments;
            this.lowQualityDocuments = lowQualityDocuments;
            this.draftDocuments = draftDocuments;
            this.publishedDocuments = publishedDocuments;
            this.archivedDocuments = archivedDocuments;
            this.deletedDocuments = deletedDocuments;
        }

        // Getters and setters
        public long getTotalDocuments() { return totalDocuments; }
        public void setTotalDocuments(long totalDocuments) { this.totalDocuments = totalDocuments; }

        public double getAverageQualityScore() { return averageQualityScore; }
        public void setAverageQualityScore(double averageQualityScore) { this.averageQualityScore = averageQualityScore; }

        public long getHighQualityDocuments() { return highQualityDocuments; }
        public void setHighQualityDocuments(long highQualityDocuments) { this.highQualityDocuments = highQualityDocuments; }

        public long getMediumQualityDocuments() { return mediumQualityDocuments; }
        public void setMediumQualityDocuments(long mediumQualityDocuments) { this.mediumQualityDocuments = mediumQualityDocuments; }

        public long getLowQualityDocuments() { return lowQualityDocuments; }
        public void setLowQualityDocuments(long lowQualityDocuments) { this.lowQualityDocuments = lowQualityDocuments; }

        public long getDraftDocuments() { return draftDocuments; }
        public void setDraftDocuments(long draftDocuments) { this.draftDocuments = draftDocuments; }

        public long getPublishedDocuments() { return publishedDocuments; }
        public void setPublishedDocuments(long publishedDocuments) { this.publishedDocuments = publishedDocuments; }

        public long getArchivedDocuments() { return archivedDocuments; }
        public void setArchivedDocuments(long archivedDocuments) { this.archivedDocuments = archivedDocuments; }

        public long getDeletedDocuments() { return deletedDocuments; }
        public void setDeletedDocuments(long deletedDocuments) { this.deletedDocuments = deletedDocuments; }
    }
}