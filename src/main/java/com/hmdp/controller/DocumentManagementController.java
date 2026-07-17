package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.ai.infrastructure.parser.ParserRegistry;
import com.hmdp.dto.Result;
import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.service.DocumentManagementService;
import com.hmdp.service.impl.DocumentManagementServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/document")
public class DocumentManagementController {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "pdf", "docx", "xlsx", "html", "htm");

    private final DocumentManagementService documentManagementService;
    private final ParserRegistry parserRegistry;

    @Value("${rag.document.max-upload-bytes:5242880}")
    private long maxUploadBytes;

    public DocumentManagementController(DocumentManagementService documentManagementService, ParserRegistry parserRegistry) {
        this.documentManagementService = documentManagementService;
        this.parserRegistry = parserRegistry;
    }

    @GetMapping("/list")
    @SaCheckPermission("document:manage")
    public Result listAllDocuments() {
        try {
            List<DocumentMetadata> documents = documentManagementService.listAllDocuments();
            return Result.ok(documents);
        } catch (Exception e) {
            log.error("List documents failed", e);
            return Result.fail("获取文档列表失败");
        }
    }

    @GetMapping("/{documentId}")
    @SaCheckPermission("document:manage")
    public Result getDocument(@PathVariable String documentId) {
        try {
            Optional<DocumentMetadata> metadata = documentManagementService.getDocumentMetadata(documentId);
            return metadata.<Result>map(Result::ok).orElseGet(() -> Result.fail("文档不存在"));
        } catch (Exception e) {
            log.error("Get document failed, documentId={}", documentId, e);
            return Result.fail("获取文档详情失败");
        }
    }

    @GetMapping("/status/{status}")
    @SaCheckPermission("document:manage")
    public Result listDocumentsByStatus(@PathVariable String status) {
        try {
            DocumentStatus documentStatus = DocumentStatus.valueOf(status.toUpperCase());
            return Result.ok(documentManagementService.listDocumentsByStatus(documentStatus));
        } catch (IllegalArgumentException e) {
            return Result.fail("无效的文档状态");
        } catch (Exception e) {
            log.error("List documents by status failed", e);
            return Result.fail("获取文档列表失败");
        }
    }

    @GetMapping("/quality")
    @SaCheckPermission("document:manage")
    public Result listDocumentsByQualityScoreRange(@RequestParam double minScore, @RequestParam double maxScore) {
        try {
            return Result.ok(documentManagementService.listDocumentsByQualityScoreRange(minScore, maxScore));
        } catch (Exception e) {
            log.error("List documents by quality failed", e);
            return Result.fail("获取文档列表失败");
        }
    }

    @GetMapping("/statistics")
    @SaCheckPermission("document:manage")
    public Result getStatistics() {
        try {
            List<DocumentMetadata> allDocuments = documentManagementService.listAllDocuments();
            long totalDocs = allDocuments.size();
            double avgQuality = allDocuments.stream().mapToDouble(DocumentMetadata::getQualityScore).average().orElse(0.0);
            long highQualityDocs = allDocuments.stream().filter(doc -> doc.getQualityScore() >= 0.8).count();
            long mediumQualityDocs = allDocuments.stream().filter(doc -> doc.getQualityScore() >= 0.5 && doc.getQualityScore() < 0.8).count();
            long lowQualityDocs = allDocuments.stream().filter(doc -> doc.getQualityScore() < 0.5).count();
            return Result.ok(new DocumentStatistics(totalDocs, avgQuality, highQualityDocs, mediumQualityDocs, lowQualityDocs));
        } catch (Exception e) {
            log.error("Get document statistics failed", e);
            return Result.fail("获取文档统计信息失败");
        }
    }

    @PostMapping("/upload")
    @SaCheckPermission("document:manage")
    public Result uploadDocument(@RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) String title,
                                 @RequestParam(required = false) String source) {
        try {
            if (file == null || file.isEmpty()) {
                return Result.fail("上传文件不能为空");
            }
            if (file.getSize() > maxUploadBytes) {
                return Result.fail("文件大小超过限制");
            }
            String safeFilename = safeFilename(file.getOriginalFilename());
            if (safeFilename == null) {
                return Result.fail("文件名不安全");
            }
            String fileType = getFileType(safeFilename);
            if (!ALLOWED_EXTENSIONS.contains(fileType)) {
                return Result.fail("不支持的文件类型");
            }
            ParserRegistry.ParseResult parseResult = parserRegistry.parse(file.getBytes(), safeFilename, file.getContentType());
            String content = parseResult.getDocument().getPlainText();
            if (content == null || content.trim().isEmpty()) {
                return Result.fail("文档内容不能为空");
            }

            DocumentMetadata metadata = new DocumentMetadata();
            metadata.setTitle(isBlank(title) ? safeFilename : title.trim());
            metadata.setSource(isBlank(source) ? "manual-upload" : source.trim());
            metadata.setFileType(fileType);
            metadata.setCreatedAt(LocalDateTime.now());
            metadata.setUpdatedAt(LocalDateTime.now());
            metadata.setStatus(DocumentStatus.PUBLISHED);

            String documentId = documentManagementService.addDocument(
                    dev.langchain4j.data.document.Document.from(content), metadata);
            DocumentMetadata savedMetadata = documentManagementService.getDocumentMetadata(documentId).orElse(metadata);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("documentId", documentId);
            resultData.put("title", savedMetadata.getTitle());
            resultData.put("wordCount", savedMetadata.getWordCount());
            resultData.put("qualityScore", savedMetadata.getQualityScore());
            resultData.put("sha256", parseResult.getSha256());
            resultData.put("mimeType", parseResult.getMimeType());
            boolean ragIndexed = documentManagementService instanceof DocumentManagementServiceImpl
                    && ((DocumentManagementServiceImpl) documentManagementService).isRagIngestionAvailable();
            resultData.put("ragIndexed", ragIndexed);
            resultData.put("message", ragIndexed
                    ? "文档上传成功，已尝试写入向量库"
                    : "文档上传成功；RAG 未启用或不可用，未写入向量库");
            return Result.ok(resultData);
        } catch (IOException e) {
            log.error("Read uploaded document failed", e);
            return Result.fail("读取上传文件失败");
        } catch (Exception e) {
            log.error("Upload document failed", e);
            return Result.fail("上传文档失败");
        }
    }

    @DeleteMapping("/{documentId}")
    @SaCheckPermission("document:manage")
    public Result deleteDocument(@PathVariable String documentId) {
        try {
            boolean success = documentManagementService.deleteDocument(documentId);
            return success ? Result.ok("文档删除成功") : Result.fail("文档不存在");
        } catch (Exception e) {
            log.error("Delete document failed, documentId={}", documentId, e);
            return Result.fail("删除文档失败");
        }
    }

    private String getFileType(String filename) {
        int lastDotIndex = filename == null ? -1 : filename.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < filename.length() - 1) {
            return filename.substring(lastDotIndex + 1).toLowerCase();
        }
        return "txt";
    }

    private String safeFilename(String filename) {
        if (isBlank(filename)) {
            return null;
        }
        String normalized = filename.trim().replace('\\', '/');
        if (normalized.contains("..")) {
            return null;
        }
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.isEmpty() ? null : name;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static class DocumentStatistics {
        private long totalDocuments;
        private double averageQualityScore;
        private long highQualityDocuments;
        private long mediumQualityDocuments;
        private long lowQualityDocuments;

        public DocumentStatistics(long totalDocuments,
                                  double averageQualityScore,
                                  long highQualityDocuments,
                                  long mediumQualityDocuments,
                                  long lowQualityDocuments) {
            this.totalDocuments = totalDocuments;
            this.averageQualityScore = averageQualityScore;
            this.highQualityDocuments = highQualityDocuments;
            this.mediumQualityDocuments = mediumQualityDocuments;
            this.lowQualityDocuments = lowQualityDocuments;
        }

        public long getTotalDocuments() {
            return totalDocuments;
        }

        public void setTotalDocuments(long totalDocuments) {
            this.totalDocuments = totalDocuments;
        }

        public double getAverageQualityScore() {
            return averageQualityScore;
        }

        public void setAverageQualityScore(double averageQualityScore) {
            this.averageQualityScore = averageQualityScore;
        }

        public long getHighQualityDocuments() {
            return highQualityDocuments;
        }

        public void setHighQualityDocuments(long highQualityDocuments) {
            this.highQualityDocuments = highQualityDocuments;
        }

        public long getMediumQualityDocuments() {
            return mediumQualityDocuments;
        }

        public void setMediumQualityDocuments(long mediumQualityDocuments) {
            this.mediumQualityDocuments = mediumQualityDocuments;
        }

        public long getLowQualityDocuments() {
            return lowQualityDocuments;
        }

        public void setLowQualityDocuments(long lowQualityDocuments) {
            this.lowQualityDocuments = lowQualityDocuments;
        }
    }
}
