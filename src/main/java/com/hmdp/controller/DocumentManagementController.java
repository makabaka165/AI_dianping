package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.ai.application.knowledge.KnowledgeIngestionApplicationService;
import com.hmdp.dto.Result;
import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.service.DocumentManagementService;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/document")
@Deprecated
public class DocumentManagementController {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "txt", "md", "pdf", "docx", "xlsx", "html", "htm");

    private final DocumentManagementService legacyDocuments;
    private final KnowledgeIngestionApplicationService knowledgeIngestion;

    @Value("${rag.document.max-upload-bytes:5242880}")
    private long maxUploadBytes;

    public DocumentManagementController(DocumentManagementService legacyDocuments,
                                        KnowledgeIngestionApplicationService knowledgeIngestion) {
        this.legacyDocuments = legacyDocuments;
        this.knowledgeIngestion = knowledgeIngestion;
    }

    @GetMapping("/list")
    @SaCheckPermission("document:manage")
    public Result listAllDocuments() {
        return Result.ok(legacyDocuments.listAllDocuments());
    }

    @GetMapping("/{documentId}")
    @SaCheckPermission("document:manage")
    public Result getDocument(@PathVariable String documentId) {
        Optional<DocumentMetadata> metadata = legacyDocuments.getDocumentMetadata(documentId);
        return metadata.<Result>map(Result::ok).orElseGet(() -> Result.fail("document not found"));
    }

    @GetMapping("/status/{status}")
    @SaCheckPermission("document:manage")
    public Result listDocumentsByStatus(@PathVariable String status) {
        try {
            return Result.ok(legacyDocuments.listDocumentsByStatus(DocumentStatus.valueOf(status.toUpperCase())));
        } catch (IllegalArgumentException e) {
            return Result.fail("invalid document status");
        }
    }

    @GetMapping("/quality")
    @SaCheckPermission("document:manage")
    public Result listDocumentsByQualityScoreRange(@RequestParam double minScore,
                                                   @RequestParam double maxScore) {
        return Result.ok(legacyDocuments.listDocumentsByQualityScoreRange(minScore, maxScore));
    }

    @GetMapping("/statistics")
    @SaCheckPermission("document:manage")
    public Result getStatistics() {
        List<DocumentMetadata> documents = legacyDocuments.listAllDocuments();
        double average = documents.stream().mapToDouble(DocumentMetadata::getQualityScore).average().orElse(0);
        return Result.ok(new DocumentStatistics(documents.size(), average,
                documents.stream().filter(value -> value.getQualityScore() >= 0.8).count(),
                documents.stream().filter(value -> value.getQualityScore() >= 0.5
                        && value.getQualityScore() < 0.8).count(),
                documents.stream().filter(value -> value.getQualityScore() < 0.5).count()));
    }

    @PostMapping("/upload")
    @SaCheckPermission("document:manage")
    public Result uploadDocument(@RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) String title,
                                 @RequestParam(required = false) String source) {
        try {
            if (file == null || file.isEmpty()) return Result.fail("file must not be empty");
            if (file.getSize() > maxUploadBytes) return Result.fail("file exceeds upload size limit");
            String fileName = safeFilename(file.getOriginalFilename());
            if (fileName == null || !ALLOWED_EXTENSIONS.contains(extension(fileName))) {
                return Result.fail("unsupported or unsafe file name");
            }
            return Result.ok(knowledgeIngestion.upload("kb-shop-enterprise", 1,
                    blank(title) ? fileName : title.trim(), fileName, file.getContentType(), file.getBytes()));
        } catch (Exception e) {
            log.warn("Legacy document upload failed, errorType={}", e.getClass().getSimpleName());
            return Result.fail("document upload failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/{documentId}")
    @SaCheckPermission("document:manage")
    public Result deleteDocument(@PathVariable String documentId) {
        try {
            knowledgeIngestion.deleteDocument(documentId);
            return Result.ok("document deleted");
        } catch (IllegalArgumentException notNewDocument) {
            return legacyDocuments.deleteDocument(documentId)
                    ? Result.ok("document deleted") : Result.fail("document not found");
        }
    }

    private String safeFilename(String value) {
        if (blank(value)) return null;
        String normalized = value.trim().replace('\\', '/');
        if (normalized.contains("..") || normalized.contains("/")) return null;
        return normalized;
    }

    private String extension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 && dot < value.length() - 1 ? value.substring(dot + 1).toLowerCase() : "";
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    public static final class DocumentStatistics {
        private final long totalDocuments;
        private final double averageQualityScore;
        private final long highQualityDocuments;
        private final long mediumQualityDocuments;
        private final long lowQualityDocuments;

        public DocumentStatistics(long totalDocuments, double averageQualityScore, long highQualityDocuments,
                                  long mediumQualityDocuments, long lowQualityDocuments) {
            this.totalDocuments = totalDocuments;
            this.averageQualityScore = averageQualityScore;
            this.highQualityDocuments = highQualityDocuments;
            this.mediumQualityDocuments = mediumQualityDocuments;
            this.lowQualityDocuments = lowQualityDocuments;
        }

        public long getTotalDocuments() { return totalDocuments; }
        public double getAverageQualityScore() { return averageQualityScore; }
        public long getHighQualityDocuments() { return highQualityDocuments; }
        public long getMediumQualityDocuments() { return mediumQualityDocuments; }
        public long getLowQualityDocuments() { return lowQualityDocuments; }
    }
}
