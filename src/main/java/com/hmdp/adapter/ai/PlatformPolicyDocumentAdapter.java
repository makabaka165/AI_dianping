package com.hmdp.adapter.ai;

import com.hmdp.ai.infra.DocumentQualityAssessment;
import com.hmdp.ai.port.PlatformPolicyDocumentPort;
import com.hmdp.ai.retrieval.PlatformPolicyVectorDocumentFactory;
import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.service.DocumentManagementService;
import dev.langchain4j.data.document.Document;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.Set;

@Component
public class PlatformPolicyDocumentAdapter implements PlatformPolicyDocumentPort {

    @Resource
    private DocumentManagementService documentManagementService;

    @Override
    public void saveImportedDocument(DocumentMetadata metadata,
                                     DocumentQualityAssessment qualityAssessment,
                                     Document document) {
        DocumentMetadata safeMetadata = metadata == null ? new DocumentMetadata() : metadata;
        applyQualityAssessment(safeMetadata, qualityAssessment);
        documentManagementService.saveDocument(document, safeMetadata);
    }

    @Override
    public boolean isActiveDocumentChunk(String documentId, String contentHash) {
        if (isBlank(documentId) || isBlank(contentHash)) {
            return false;
        }
        return documentManagementService.getDocumentMetadata(documentId)
                .filter(metadata -> metadata.getStatus() == DocumentStatus.PUBLISHED)
                .flatMap(metadata -> documentManagementService.getDocument(documentId))
                .map(document -> contentHash.equals(PlatformPolicyVectorDocumentFactory.contentHash(documentId, document.text())))
                .orElse(false);
    }

    @Override
    public void archiveMissingImportedDocuments(Set<String> activeDocumentIds) {
        Set<String> activeIds = activeDocumentIds == null ? Collections.emptySet() : activeDocumentIds;
        for (DocumentMetadata metadata : documentManagementService.listAllDocuments()) {
            if (metadata == null
                    || !"system-initial-import".equals(metadata.getSource())
                    || isBlank(metadata.getId())
                    || activeIds.contains(metadata.getId())) {
                continue;
            }
            DocumentMetadata archived = copy(metadata);
            archived.setStatus(DocumentStatus.ARCHIVED);
            documentManagementService.updateDocument(archived.getId(), null, archived);
        }
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private DocumentMetadata copy(DocumentMetadata source) {
        DocumentMetadata copy = new DocumentMetadata();
        copy.setId(source.getId());
        copy.setTitle(source.getTitle());
        copy.setSource(source.getSource());
        copy.setFileType(source.getFileType());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setVersion(source.getVersion());
        copy.setStatus(source.getStatus());
        copy.setQualityScore(source.getQualityScore());
        copy.setQualityProfile(source.getQualityProfile());
        copy.setQualityLevel(source.getQualityLevel());
        copy.setQualityDimensions(source.getQualityDimensions());
        copy.setQualityIssues(source.getQualityIssues());
        copy.setQualitySuggestions(source.getQualitySuggestions());
        copy.setWordCount(source.getWordCount());
        copy.setKeywords(source.getKeywords());
        return copy;
    }
}
