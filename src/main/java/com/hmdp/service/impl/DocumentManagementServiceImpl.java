package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.infra.DocumentQualityAssessment;
import com.hmdp.ai.infra.DocumentQualityAssessor;
import com.hmdp.ai.infra.DocumentQualityProfile;
import com.hmdp.ai.retrieval.PlatformPolicyVectorDocumentFactory;
import com.hmdp.entity.AiDocument;
import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.mapper.AiDocumentMapper;
import com.hmdp.service.DocumentManagementService;
import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocumentManagementServiceImpl implements DocumentManagementService {

    @Autowired
    private AiDocumentMapper aiDocumentMapper;

    @Autowired
    private DocumentQualityAssessor qualityAssessor;

    @Override
    public void saveDocument(DocumentMetadata metadata) {
        saveDocument(null, metadata);
    }

    @Override
    public void saveDocument(Document document, DocumentMetadata metadata) {
        DocumentMetadata safeMetadata = prepareMetadata(metadata, document);
        upsert(toRecord(safeMetadata, document == null ? null : document.text()));
        log.info("Saved AI document metadata, id={}, title={}", safeMetadata.getId(), safeMetadata.getTitle());
    }

    @Override
    public String addDocument(Document document, DocumentMetadata metadata) {
        if (document == null || blank(document.text())) {
            throw new IllegalArgumentException("document content must not be blank");
        }
        DocumentMetadata safeMetadata = prepareMetadata(metadata, document);
        upsert(toRecord(safeMetadata, document.text()));
        log.info("Added AI document, id={}, title={}, qualityScore={}",
                safeMetadata.getId(), safeMetadata.getTitle(), safeMetadata.getQualityScore());
        return safeMetadata.getId();
    }

    @Override
    public boolean updateDocument(String documentId, Document document, DocumentMetadata metadata) {
        AiDocument existing = aiDocumentMapper.selectById(documentId);
        if (existing == null || DocumentStatus.DELETED.name().equals(existing.getStatus())) {
            return false;
        }
        DocumentMetadata merged = metadata == null ? toMetadata(existing) : metadata;
        merged.setId(documentId);
        if (merged.getCreatedAt() == null) {
            merged.setCreatedAt(existing.getCreatedAt());
        }
        merged.setUpdatedAt(LocalDateTime.now());
        Document safeDocument = document == null ? Document.from(existing.getContent() == null ? "" : existing.getContent()) : document;
        DocumentMetadata prepared = prepareMetadata(merged, safeDocument);
        upsert(toRecord(prepared, safeDocument.text()));
        return true;
    }

    @Override
    public boolean deleteDocument(String documentId) {
        AiDocument existing = aiDocumentMapper.selectById(documentId);
        if (existing == null || DocumentStatus.DELETED.name().equals(existing.getStatus())) {
            return false;
        }
        existing.setStatus(DocumentStatus.DELETED.name());
        existing.setUpdatedAt(LocalDateTime.now());
        aiDocumentMapper.updateById(existing);
        log.info("Marked AI document as deleted, id={}", documentId);
        return true;
    }

    @Override
    public Optional<DocumentMetadata> getDocumentMetadata(String documentId) {
        AiDocument record = aiDocumentMapper.selectById(documentId);
        if (record == null || DocumentStatus.DELETED.name().equals(record.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(toMetadata(record));
    }

    @Override
    public Optional<Document> getDocument(String documentId) {
        AiDocument record = aiDocumentMapper.selectById(documentId);
        if (record == null || DocumentStatus.DELETED.name().equals(record.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(Document.from(record.getContent() == null ? "" : record.getContent(),
                PlatformPolicyVectorDocumentFactory.metadata(record.getId(), toMetadata(record), record.getContent())));
    }

    @Override
    public List<DocumentMetadata> listAllDocuments() {
        List<AiDocument> records = aiDocumentMapper.selectList(new QueryWrapper<AiDocument>()
                .ne("status", DocumentStatus.DELETED.name())
                .orderByDesc("updated_at"));
        return toMetadataList(records);
    }

    @Override
    public List<DocumentMetadata> listDocumentsByStatus(DocumentStatus status) {
        if (status == null) {
            return Collections.emptyList();
        }
        List<AiDocument> records = aiDocumentMapper.selectList(new QueryWrapper<AiDocument>()
                .eq("status", status.name())
                .orderByDesc("updated_at"));
        return toMetadataList(records);
    }

    @Override
    public List<DocumentMetadata> listDocumentsByQualityScoreRange(double minScore, double maxScore) {
        List<AiDocument> records = aiDocumentMapper.selectList(new QueryWrapper<AiDocument>()
                .ne("status", DocumentStatus.DELETED.name())
                .between("quality_score", minScore, maxScore)
                .orderByDesc("quality_score"));
        return toMetadataList(records);
    }

    private DocumentMetadata prepareMetadata(DocumentMetadata metadata, Document document) {
        DocumentMetadata safeMetadata = metadata == null ? new DocumentMetadata() : metadata;
        if (blank(safeMetadata.getId())) {
            safeMetadata.setId(UUID.randomUUID().toString());
        }
        if (blank(safeMetadata.getTitle())) {
            safeMetadata.setTitle("untitled-document");
        }
        if (blank(safeMetadata.getSource())) {
            safeMetadata.setSource("manual-upload");
        }
        if (blank(safeMetadata.getFileType())) {
            safeMetadata.setFileType("txt");
        }
        if (safeMetadata.getCreatedAt() == null) {
            safeMetadata.setCreatedAt(LocalDateTime.now());
        }
        safeMetadata.setUpdatedAt(LocalDateTime.now());
        if (safeMetadata.getStatus() == null) {
            safeMetadata.setStatus(DocumentStatus.PUBLISHED);
        }
        if (document != null && blank(safeMetadata.getQualityProfile())) {
            DocumentQualityAssessment assessment = qualityAssessor.assess(document, DocumentQualityProfile.GENERAL);
            applyQualityAssessment(safeMetadata, assessment);
        }
        if (document != null) {
            safeMetadata.setWordCount(document.text() == null ? 0 : document.text().length());
        }
        return safeMetadata;
    }

    private void upsert(AiDocument record) {
        AiDocument existing = aiDocumentMapper.selectById(record.getId());
        if (existing == null) {
            aiDocumentMapper.insert(record);
        } else {
            aiDocumentMapper.updateById(record);
        }
    }

    private AiDocument toRecord(DocumentMetadata metadata, String content) {
        AiDocument record = new AiDocument();
        record.setId(metadata.getId());
        record.setTitle(metadata.getTitle());
        record.setSource(metadata.getSource());
        record.setFileType(metadata.getFileType());
        record.setStatus(metadata.getStatus() == null ? DocumentStatus.PUBLISHED.name() : metadata.getStatus().name());
        record.setQualityScore(metadata.getQualityScore());
        record.setQualityProfile(metadata.getQualityProfile());
        record.setQualityLevel(metadata.getQualityLevel());
        record.setWordCount(metadata.getWordCount());
        record.setKeywords(joinKeywords(metadata.getKeywords()));
        record.setContent(content);
        record.setCreatedAt(metadata.getCreatedAt());
        record.setUpdatedAt(metadata.getUpdatedAt());
        return record;
    }

    private DocumentMetadata toMetadata(AiDocument record) {
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setId(record.getId());
        metadata.setTitle(record.getTitle());
        metadata.setSource(record.getSource());
        metadata.setFileType(record.getFileType());
        metadata.setCreatedAt(record.getCreatedAt());
        metadata.setUpdatedAt(record.getUpdatedAt());
        metadata.setStatus(parseStatus(record.getStatus()));
        metadata.setQualityScore(record.getQualityScore() == null ? 0.0 : record.getQualityScore());
        metadata.setQualityProfile(record.getQualityProfile());
        metadata.setQualityLevel(record.getQualityLevel());
        metadata.setWordCount(record.getWordCount() == null ? 0L : record.getWordCount());
        metadata.setKeywords(splitKeywords(record.getKeywords()));
        return metadata;
    }

    private List<DocumentMetadata> toMetadataList(List<AiDocument> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return records.stream().map(this::toMetadata).collect(Collectors.toList());
    }

    private DocumentStatus parseStatus(String status) {
        try {
            return status == null ? DocumentStatus.PUBLISHED : DocumentStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return DocumentStatus.PUBLISHED;
        }
    }

    private String joinKeywords(String[] keywords) {
        if (keywords == null || keywords.length == 0) {
            return "";
        }
        return Arrays.stream(keywords)
                .filter(value -> !blank(value))
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(","));
    }

    private String[] splitKeywords(String keywords) {
        if (blank(keywords)) {
            return new String[0];
        }
        return Arrays.stream(keywords.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
    }

    private void applyQualityAssessment(DocumentMetadata metadata, DocumentQualityAssessment assessment) {
        if (metadata == null || assessment == null) {
            return;
        }
        metadata.setQualityScore(assessment.getScore());
        metadata.setQualityProfile(assessment.getProfile().name());
        metadata.setQualityLevel(assessment.getLevel().name());
        metadata.setQualityDimensions(assessment.getDimensionScores());
        metadata.setQualityIssues(assessment.getIssues());
        metadata.setQualitySuggestions(assessment.getSuggestions());
        metadata.setKeywords(assessment.getKeywords().toArray(new String[0]));
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
