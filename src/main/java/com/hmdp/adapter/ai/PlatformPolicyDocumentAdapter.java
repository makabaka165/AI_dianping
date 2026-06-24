package com.hmdp.adapter.ai;

import com.hmdp.ai.infra.DocumentQualityAssessment;
import com.hmdp.ai.port.PlatformPolicyDocumentPort;
import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.service.DocumentManagementService;
import dev.langchain4j.data.document.Document;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Component
public class PlatformPolicyDocumentAdapter implements PlatformPolicyDocumentPort {

    @Resource
    private DocumentManagementService documentManagementService;

    @Override
    public void saveImportedDocument(String title,
                                     String source,
                                     String fileType,
                                     LocalDateTime createdAt,
                                     LocalDateTime updatedAt,
                                     DocumentQualityAssessment qualityAssessment,
                                     int wordCount,
                                     Document document) {
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setTitle(title);
        metadata.setSource(source);
        metadata.setFileType(fileType);
        metadata.setCreatedAt(createdAt);
        metadata.setUpdatedAt(updatedAt);
        metadata.setStatus(DocumentStatus.PUBLISHED);
        metadata.setWordCount(wordCount);
        applyQualityAssessment(metadata, qualityAssessment);
        documentManagementService.saveDocument(metadata);
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
}
